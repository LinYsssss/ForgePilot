package com.example.codereview.sandbox;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single source of truth for the workspace archive reference carried in
 * {@link SandboxJob#workspaceArchiveRef()}.
 *
 * <p>Kept as an isomorphic mirror across backend and runner (same pattern as
 * {@link SandboxJob}/{@link SandboxJobSigner}): the backend encodes, the runner parses, and a
 * golden wire-format test in each module pins the same literals so neither side can drift alone.
 * This exists because the two sides once drifted apart — the backend emitted
 * {@code workspace://...} while the runner rejected every reference containing a colon, so the
 * archive hand-off failed unconditionally in production.
 *
 * <p>A reference is deliberately a bare file name: no scheme, no directory separators.
 * {@link #parse} carries every syntactic safety rule (scheme, traversal, character whitelist,
 * length). Resolving the name under the archive root, the real-path fence and the regular-file
 * check remain the runner's responsibility.
 */
public final class WorkspaceArchiveReference {

    /** Length cap, carried over from the backend's historical request validation. */
    public static final int MAX_LENGTH = 512;

    /** Bare file name: starts alphanumeric (rejects leading {@code -} and {@code .}), no separators. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern HEX_ID = Pattern.compile("[0-9a-fA-F]{7,80}");

    private WorkspaceArchiveReference() {
    }

    /** Encodes the reference of an agent-run workspace archive (repository tree + prepared diff). */
    public static String forAgentRun(Long agentRunId, String headSha) {
        if (agentRunId == null || agentRunId <= 0) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        if (headSha == null || !HEX_ID.matcher(headSha).matches()) {
            throw new IllegalArgumentException("head SHA is invalid for workspace archive");
        }
        return parse("agent-run-" + agentRunId + "-" + headSha.toLowerCase(Locale.ROOT) + ".tar");
    }

    /** Encodes the reference of a patch-validation workspace archive (tree + candidate patch). */
    public static String forPatch(Long patchId, String patchHash) {
        if (patchId == null || patchId <= 0) {
            throw new IllegalArgumentException("persisted patch candidate is required");
        }
        if (patchHash == null || !HEX_ID.matcher(patchHash).matches()) {
            throw new IllegalArgumentException("patch hash is invalid for workspace archive");
        }
        return parse("patch-" + patchId + "-" + patchHash.toLowerCase(Locale.ROOT) + ".tar");
    }

    /**
     * Validates a reference and returns it unchanged as a safe bare file name. Rejects null, blank,
     * oversize, traversal ({@code ..}), and everything outside the character whitelist — which by
     * construction rejects any scheme ({@code :}), any path separator ({@code /}, {@code \}), and
     * leading {@code -} or {@code .}.
     */
    public static String parse(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > MAX_LENGTH
                || reference.contains("..") || !SAFE_NAME.matcher(reference).matches()) {
            throw new IllegalArgumentException("workspace archive reference is invalid");
        }
        return reference;
    }
}
