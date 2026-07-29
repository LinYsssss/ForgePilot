package com.example.reposage.sandbox;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 防重放:同一个 nonce 只接受一次。
 *
 * <p>此前用的是进程生命周期内只增不减的 {@code Set} —— 服务跑得越久占用越大,而 nonce
 * 随任务过期后本就无需再记。这里改为按 TTL 淘汰并设容量上限。TTL 必须不短于任务自身的
 * 有效期,否则记录先于任务过期被清掉,重放窗口会重新打开。
 *
 * <p>仍是**单实例**语义:多副本共享防重放需要外部存储,不在本次范围内。
 */
public final class SandboxReplayGuard {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier clock;

    public SandboxReplayGuard() {
        this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    public SandboxReplayGuard(Duration ttl, int maxEntries, LongSupplier clock) {
        this.ttlMillis = Math.max(1, ttl.toMillis());
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    /**
     * 记录 nonce 并返回它是否首次出现。
     *
     * @return 首次出现返回 {@code true};是重放返回 {@code false}
     */
    public boolean checkAndRecord(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        long now = clock.getAsLong();
        evictExpired(now);
        // 容量兜底:清完过期项仍超限,说明短时间内涌入了异常多的 nonce。
        // 此时宁可清空重来也不能无限增长,代价是极端情况下可能放过一次重放。
        if (seen.size() >= maxEntries) {
            seen.clear();
        }
        Long previous = seen.putIfAbsent(nonce, now);
        if (previous == null) {
            return true;
        }
        if (now - previous >= ttlMillis) {
            // 记录已过期,对应任务也早已失效,重新计为首次
            seen.put(nonce, now);
            return true;
        }
        return false;
    }

    /** 供测试观察当前保留的条目数。 */
    int size() {
        evictExpired(clock.getAsLong());
        return seen.size();
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= ttlMillis) {
                it.remove();
            }
        }
    }
}
