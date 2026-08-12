package com.acme.toolbench.orchestration;

import com.acme.toolbench.common.error.TransientToolException;
import com.acme.toolbench.procio.ProcessOutputPump;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 外部分析工具执行编排：命令组装、超时控制、瞬态失败重试、结果归一。
 *
 * <p>输出收集（后台抽干 + 容量上限）由 {@link ProcessOutputPump} 承担；
 * 瞬态异常归位 common.error。本次仅包结构归位，公共签名零变更。
 */
public class ToolRunService {

    private static final long TOOL_TIMEOUT_SECONDS = 120;
    private static final int MAX_TRANSIENT_RETRIES = 2;

    private final Map<String, List<String>> toolCommands;

    public ToolRunService(Map<String, List<String>> toolCommands) {
        this.toolCommands = toolCommands;
    }

    /**
     * 执行指定工具，瞬态失败（进程池抖动、管道过早关闭）最多重试两次。
     */
    public ToolRunResult run(String toolName, Path workspace) {
        List<String> command = toolCommands.get(toolName);
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("未注册的工具: " + toolName);
        }
        TransientToolException lastTransient = null;
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                return runOnce(toolName, command, workspace);
            } catch (TransientToolException ex) {
                lastTransient = ex;
            }
        }
        throw lastTransient;
    }

    private ToolRunResult runOnce(String toolName, List<String> command, Path workspace) {
        Process process = null;
        try {
            process = new ProcessBuilder(new ArrayList<>(command))
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
            ProcessOutputPump pump = ProcessOutputPump.start(process.getInputStream());
            boolean finished = process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                pump.awaitQuietly();
                return ToolRunResult.timeout(toolName);
            }
            String output = pump.awaitQuietly();
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ToolRunResult.success(toolName, output, pump.isTruncated());
            }
            if (isTransientExit(exitCode)) {
                throw new TransientToolException(toolName + " 瞬态退出码: " + exitCode);
            }
            return ToolRunResult.failure(toolName, exitCode, output, pump.isTruncated());
        } catch (IOException ex) {
            throw new TransientToolException("工具进程启动失败: " + toolName);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工具执行被中断: " + toolName);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 128+SIGPIPE / 129 类退出码视为瞬态，交给上层重试。 */
    private boolean isTransientExit(int exitCode) {
        return exitCode == 141 || exitCode == 129;
    }
}
