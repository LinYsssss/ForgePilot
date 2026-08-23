package com.forgepilot.scm;

import java.time.Instant;
import java.util.List;

/**
 * provider 所声明的 PR 当前状态。一次 webhook 投递只是信号；本对象来自一次
 * 全新的权威读取，这正是重放无害、乱序可检测的原因。
 *
 * @param sourceRevision provider 若有稳定的 diff 修订号则为该值。GitHub 不提供，
 *     因此在 GitHub 上为 null；它只用于事件定序，绝不进入指纹。
 */
public record PullRequestSnapshot(
        int externalNumber,
        String baseSha,
        String headSha,
        String headRef,
        String title,
        String sourceRevision,
        Instant sourceUpdatedAt,
        String authorExternalUserId,
        String authorUsername,
        List<ChangedFile> changedFiles) {
}
