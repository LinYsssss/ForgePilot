package com.forgepilot.review;

/** Human decision committed by the publishing transaction; listeners run after commit. */
public record ReviewDecided(long projectId, long reviewId) { }
