package com.acme.toolbench.orchestration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 工具输出收集：后台线程抽干进程管道，避免 waitFor 与写满的管道互等死锁。
 *
 * <p>捕获容量有硬上限：超过 {@link #MAX_CAPTURE_CHARS} 后停止追加、只抽
 * 不存，并标记 truncated，防止 SBOM/依赖树这类超大输出撑爆堆。
 */
final class ToolOutputCollector {

    /** 捕获上限（字符）。超过该值停止追加，只抽干不再保存。 */
    private static final int MAX_CAPTURE_CHARS = 2_000_000;

    private final Thread worker;
    private final StringBuilder captured = new StringBuilder();
    private volatile boolean truncated;

    private ToolOutputCollector(InputStream stream) {
        this.worker = new Thread(() -> {
            byte[] chunk = new byte[8192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    if (captured.length() < MAX_CAPTURE_CHARS) {
                        captured.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时管道关闭属预期。
            }
        }, "tool-output-collector");
        this.worker.setDaemon(true);
    }

    static ToolOutputCollector start(InputStream stream) {
        ToolOutputCollector collector = new ToolOutputCollector(stream);
        collector.worker.start();
        return collector;
    }

    /** 等待抽干线程收尾（最多 5 秒），返回已捕获输出。 */
    String awaitQuietly() {
        try {
            worker.join(5000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return captured.toString();
    }

    boolean isTruncated() {
        return truncated;
    }
}
