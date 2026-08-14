package com.example.codereview.clienterror;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 落日志出口的三条硬约束:WARN + 专用 marker、总量 4KB 截断(截断不拒绝)、
 * 不可信输入无法伪造第二条日志行(日志注入)。
 */
class ClientErrorReportServiceTest {

    private final ClientErrorReportService service = new ClientErrorReportService();
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("client.error");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private ILoggingEvent only() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Test
    void writesOneWarnLineWithTheDedicatedMarker() {
        service.record(new ClientErrorReport(
                "Uncaught TypeError: x is not a function",
                "TypeError: x is not a function\n    at submit (app.js:10:3)",
                "https://reposage.local/agent", 1755150000000L));

        ILoggingEvent event = only();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getMarkerList()).extracting(org.slf4j.Marker::getName).containsExactly("CLIENT_ERROR");
        assertThat(event.getFormattedMessage())
                .contains("event=client_error")
                .contains("ts=1755150000000")
                .contains("url=https://reposage.local/agent")
                .contains("Uncaught TypeError");
    }

    /** 换行转义为字面 \n:栈帧边界可读、可离线还原,但整条日志恒为单行。 */
    @Test
    void newlinesAreEscapedSoTheRecordStaysOneLine() {
        service.record(new ClientErrorReport(
                "boom\nWARN [fake] event=client_error message=forged",
                "line1\r\nline2", "https://x/", 1L));

        String line = only().getFormattedMessage();
        assertThat(line.lines()).hasSize(1);
        assertThat(line).contains("boom\\nWARN").contains("line1\\nline2");
    }

    @Test
    void otherControlCharactersAreNeutralized() {
        // NUL 与 ESC(ANSI 染色引导符)这类控制字符不许透传到日志
        service.record(new ClientErrorReport("bad\0\033[31mred", null, "https://x/", 1L));

        assertThat(only().getFormattedMessage()).contains("bad__[31mred");
    }

    @Test
    void oversizedStackIsTruncatedWithinTheTotalBudget() {
        String url = "https://reposage.local/agent";
        String message = "Uncaught RangeError";
        service.record(new ClientErrorReport(message, "x".repeat(20_000), url, 1L));

        String line = only().getFormattedMessage();
        assertThat(line).endsWith(ClientErrorReportService.TRUNCATION_MARK);
        // 三个文本字段合计不超过 4KB 预算:stack 吃掉 url/message 之外的剩余额度
        int stackLength = line.substring(line.indexOf("stack=") + "stack=".length()).length();
        assertThat(url.length() + message.length() + stackLength)
                .isEqualTo(ClientErrorReportService.TOTAL_BUDGET);
    }

    @Test
    void urlAndMessageHaveTheirOwnCapsSoStackKeepsMostOfTheBudget() {
        service.record(new ClientErrorReport("m".repeat(5_000), "s".repeat(5_000), "https://x/?" + "q".repeat(5_000), 1L));

        String line = only().getFormattedMessage();
        int urlStart = line.indexOf("url=") + "url=".length();
        int urlEnd = line.indexOf(" message=");
        int messageEnd = line.indexOf(" stack=");
        assertThat(urlEnd - urlStart).isEqualTo(ClientErrorReportService.URL_BUDGET);
        assertThat(messageEnd - (urlEnd + " message=".length())).isEqualTo(ClientErrorReportService.MESSAGE_BUDGET);
    }

    /** stack 可空(sendBeacon 侧 unhandledrejection 常拿不到栈),落占位符而不是 "null"。 */
    @Test
    void missingStackFallsBackToAPlaceholder() {
        service.record(new ClientErrorReport("boom", null, "https://x/", 1L));

        assertThat(only().getFormattedMessage()).endsWith("stack=-");
    }
}
