package com.example.reposage.sandbox;

/**
 * Terminal status of a sandbox job. Mirror of the backend enum. {@link #ENVIRONMENT_INCOMPLETE} is
 * distinct from {@link #FAILED}: dependencies/tooling were unavailable, so the result must NOT be
 * turned into a code finding.
 */
public enum SandboxJobStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED,
    ENVIRONMENT_INCOMPLETE
}
