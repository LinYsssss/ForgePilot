package com.example.codereview.sandbox;

/**
 * Terminal status of a sandbox job. {@link #ENVIRONMENT_INCOMPLETE} is distinct from
 * {@link #FAILED}: it means dependencies/tooling were unavailable, so the result must NOT be turned
 * into a code finding (see dependency preparation).
 */
public enum SandboxJobStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED,
    ENVIRONMENT_INCOMPLETE
}
