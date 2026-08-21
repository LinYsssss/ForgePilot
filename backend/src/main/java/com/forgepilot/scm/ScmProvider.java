package com.forgepilot.scm;

/**
 * The source control providers this deployment can talk to. The database CHECK on
 * {@code scm_repository.provider} also accepts {@code GITLAB}, which V5 reserves
 * for Phase 8; it is deliberately absent here, because a constant in this enum is
 * a claim that the code can serve it.
 */
public enum ScmProvider {
    GITHUB
}
