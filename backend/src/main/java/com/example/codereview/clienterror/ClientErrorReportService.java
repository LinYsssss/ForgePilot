package com.example.codereview.clienterror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Service;

/**
 * 前端错误上报的落日志出口:只写日志,不入库——错误上报是尽力而为的诊断信号,
 * 不值得为它建表;部署侧按独立 logger 名 {@code client.error} 或 marker
 * {@code CLIENT_ERROR} 单独路由/采集(与 security.audit 的独立事件流同一思路)。
 *
 * <p>载荷是匿名端点收进来的完全不可信输入,落盘前必须两道处理:
 * <ol>
 *   <li>控制字符清洗——换行转义为字面 {@code \n},其余控制符替换为下划线,
 *       保证一次上报恒为一行日志,攻击者无法用嵌入换行伪造第二条日志记录(日志注入);
 *   <li>总量截断——message/url/stack 合计不超过 4KB(与 PRD 的载荷口径对齐),
 *       超限截断而不是拒绝,截断处带可见标记让读日志的人知道内容不完整。
 * </ol>
 */
@Service
public class ClientErrorReportService {

    /** 独立 logger 名,部署侧可单独路由;级别恒 WARN——前端报错是降级信号,不是后端故障(ERROR 会稀释告警)。 */
    private static final Logger log = LoggerFactory.getLogger("client.error");

    /** 专用 marker:采集器按它过滤前端错误流,与后端业务日志隔离。 */
    static final Marker CLIENT_ERROR = MarkerFactory.getMarker("CLIENT_ERROR");

    /** 三个文本字段合计的落盘预算(字符),对齐「载荷 ≤4KB」的契约口径。 */
    static final int TOTAL_BUDGET = 4096;
    /** url 上限:定位页面足够,不让超长 query 串挤占堆栈预算。 */
    static final int URL_BUDGET = 512;
    /** message 上限:浏览器错误消息通常一行,1KB 已极宽裕。 */
    static final int MESSAGE_BUDGET = 1024;
    /** stack 吃掉剩余预算:它最有诊断价值,也最可能超长。 */
    static final String TRUNCATION_MARK = "...(truncated)";

    public void record(ClientErrorReport report) {
        String url = bound(sanitize(report.url()), URL_BUDGET);
        String message = bound(sanitize(report.message()), MESSAGE_BUDGET);
        String stack = bound(sanitize(report.stack()), TOTAL_BUDGET - url.length() - message.length());
        log.warn(CLIENT_ERROR, "event=client_error ts={} url={} message={} stack={}",
                report.ts(), url, message, stack);
    }

    /**
     * 换行转成字面 {@code \n}(保留栈帧边界可读、可离线还原),{@code \r} 丢弃(CRLF 只留一侧),
     * 其余控制字符替换为下划线。清洗在截断之前做:转义会让字符串变长,先截后洗会超预算。
     */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                continue;
            } else if (c < 0x20 || c == 0x7F) {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 超限截断并留可见标记;预算小到装不下标记时按预算硬切,任何情况下不越界。 */
    private static String bound(String value, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (value.length() <= budget) {
            return value;
        }
        if (budget <= TRUNCATION_MARK.length()) {
            return value.substring(0, budget);
        }
        return value.substring(0, budget - TRUNCATION_MARK.length()) + TRUNCATION_MARK;
    }
}
