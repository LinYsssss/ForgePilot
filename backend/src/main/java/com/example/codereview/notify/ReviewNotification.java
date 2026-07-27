package com.example.codereview.notify;

/**
 * 一次审查完成后对外通知的载荷。刻意只带结论摘要,不带 diff 或证据原文,
 * 避免把代码内容推到外部 IM。
 */
public record ReviewNotification(
        Long projectId,
        String projectName,
        Long reportId,
        String commitId,
        String overallRisk,
        int issueCount,
        int highCount,
        String summary
) {
}
