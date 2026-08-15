package com.example.reposage.sandbox;

/**
 * runner 针对一个 {@link SandboxJob} 返回的结果,backend 同名 record 的镜像。
 * 输出是**有界预览**并带 {@code truncated} 标志,绝不是容器输出的无界倾倒。
 */
public record SandboxResult(
        String jobId,
        SandboxJobStatus status,
        Integer exitCode,
        String outputPreview,
        boolean truncated,
        String message
) {
    public SandboxResult {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        outputPreview = outputPreview == null ? "" : outputPreview;
    }
}
