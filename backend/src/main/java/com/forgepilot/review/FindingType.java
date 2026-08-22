package com.forgepilot.review;

/**
 * What kind of problem a Finding reports.
 *
 * <p>The distinction is load-bearing for {@code finding_key}: a
 * {@link #CODE_QUALITY} key is path plus normalized position plus category,
 * while a {@link #REQUIREMENT} key must also carry {@code requirement_id} and
 * {@code ac_key} (ARCHITECTURE.md 3.6). It is also enforced in the schema —
 * a CODE_QUALITY finding may not reference an acceptance criterion.
 */
public enum FindingType {

    CODE_QUALITY,
    REQUIREMENT
}
