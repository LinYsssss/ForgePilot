package com.forgepilot.review;

/** Published after a Review is durably moved to FAILED. */
public record ReviewFailed(long projectId, long reviewId) {
}
