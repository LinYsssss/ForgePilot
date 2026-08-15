package com.example.reposage.sandbox;

import java.util.List;

/**
 * 交给 sandbox runner 的一份**已签名**工作单元,backend {@code SandboxJob} 的镜像;
 * 保持逐字节兼容,签名才能跨模块边界验通。
 *
 * <p>它带齐 runner 启动受限容器所需的一切,同时不带任何能让 runner 自行扩权的东西:
 * job id、工作区归档引用(绝不内联密钥或 provider host)、钉死的镜像 digest、
 * 白名单内的命令 id 及其参数、资源上限、绝对过期时刻,以及防重放的 nonce。
 */
public record SandboxJob(
        String jobId,
        String workspaceArchiveRef,
        String imageDigest,
        String commandId,
        List<String> args,
        Limits limits,
        long expiryEpochSeconds,
        String nonce
) {
    /** 强制施加在容器上的资源硬上限。 */
    public record Limits(int cpuMillis, int memoryMb, int pids, long timeoutMs) {
        public Limits {
            if (cpuMillis <= 0 || memoryMb <= 0 || pids <= 0 || timeoutMs <= 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    public SandboxJob {
        requireText(jobId, "jobId");
        requireText(workspaceArchiveRef, "workspaceArchiveRef");
        requireText(imageDigest, "imageDigest");
        requireText(commandId, "commandId");
        requireText(nonce, "nonce");
        if (limits == null) {
            throw new IllegalArgumentException("limits is required");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
