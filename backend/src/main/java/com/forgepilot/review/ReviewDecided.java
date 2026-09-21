package com.forgepilot.review;

/** 在决定事务内发布；监听器以 {@code @TransactionalEventListener} 在提交后运行，回滚则不发。 */
public record ReviewDecided(long projectId, long reviewId) { }
