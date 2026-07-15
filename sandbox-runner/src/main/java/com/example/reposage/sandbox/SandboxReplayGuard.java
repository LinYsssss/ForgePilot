package com.example.reposage.sandbox;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory guard against replayed sandbox jobs: a nonce may be accepted at most once. Mirror of the
 * backend guard; consulted once per delivery after signature/expiry checks. Thread-safe.
 */
public final class SandboxReplayGuard {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * Records the nonce and reports whether it was fresh.
     *
     * @return {@code true} if this nonce had not been seen before; {@code false} if it is a replay
     */
    public boolean checkAndRecord(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        return seen.add(nonce);
    }
}
