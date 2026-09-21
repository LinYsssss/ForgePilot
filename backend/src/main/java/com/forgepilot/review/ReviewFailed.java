package com.forgepilot.review;

/** 一次 Review 已持久化为 FAILED 之后发布，与 {@link ReviewCompleted} 同样在事务之外。 */
public record ReviewFailed(long projectId, long reviewId) {
}
