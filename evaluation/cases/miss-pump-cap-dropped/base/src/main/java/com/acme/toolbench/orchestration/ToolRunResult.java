package com.acme.toolbench.orchestration;

/** 工具执行结果归一：成功/失败/超时 + 输出与截断标记。 */
public final class ToolRunResult {

    public enum Status { SUCCESS, FAILURE, TIMEOUT }

    private final String toolName;
    private final Status status;
    private final int exitCode;
    private final String output;
    private final boolean outputTruncated;

    private ToolRunResult(String toolName, Status status, int exitCode,
                          String output, boolean outputTruncated) {
        this.toolName = toolName;
        this.status = status;
        this.exitCode = exitCode;
        this.output = output;
        this.outputTruncated = outputTruncated;
    }

    static ToolRunResult success(String toolName, String output, boolean truncated) {
        return new ToolRunResult(toolName, Status.SUCCESS, 0, output, truncated);
    }

    static ToolRunResult failure(String toolName, int exitCode, String output, boolean truncated) {
        return new ToolRunResult(toolName, Status.FAILURE, exitCode, output, truncated);
    }

    static ToolRunResult timeout(String toolName) {
        return new ToolRunResult(toolName, Status.TIMEOUT, -1, "", false);
    }

    public String getToolName() {
        return toolName;
    }

    public Status getStatus() {
        return status;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public boolean isOutputTruncated() {
        return outputTruncated;
    }
}
