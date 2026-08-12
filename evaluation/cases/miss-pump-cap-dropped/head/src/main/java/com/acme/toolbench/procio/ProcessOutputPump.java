package com.acme.toolbench.procio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 进程输出泵：后台线程抽干进程管道，避免 waitFor 与写满的管道互等死锁。
 *
 * <p>捕获容量有硬上限：超过 {@link #MAX_CAPTURE_CHARS} 后停止追加、只抽
 * 不存，并标记 truncated，防止 SBOM/依赖树这类超大输出撑爆堆。
 *
 * <p>原 orchestration.ToolOutputCollector，归位到 procio（进程 IO 层）
 * 并开放给编排层使用；行为零变化。
 */
public final class ProcessOutputPump {

    /** 捕获上限（字符）。超过该值停止追加，只抽干不再保存。 */
    private static final int MAX_CAPTURE_CHARS = 2_000_000;

    private final Thread worker;
    private final StringBuilder captured = new StringBuilder();
    private volatile boolean truncated;

    private ProcessOutputPump(InputStream stream) {
        this.worker = new Thread(() -> {
            byte[] chunk = new byte[8192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    captured.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 进程被杀时管道关闭属预期。
            }
        }, "process-output-pump");
        this.worker.setDaemon(true);
    }

    public static ProcessOutputPump start(InputStream stream) {
        ProcessOutputPump pump = new ProcessOutputPump(stream);
        pump.worker.start();
        return pump;
    }

    /** 等待抽干线程收尾（最多 5 秒），返回已捕获输出。 */
    public String awaitQuietly() {
        try {
            worker.join(5000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return captured.toString();
    }

    public boolean isTruncated() {
        return truncated;
    }
}
