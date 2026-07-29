package com.example.codereview.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SandboxReplayGuardTest {

    @Test
    void acceptsANonceOnceAndRejectsTheReplay() {
        SandboxReplayGuard guard = new SandboxReplayGuard();

        assertThat(guard.checkAndRecord("nonce-1")).isTrue();
        assertThat(guard.checkAndRecord("nonce-1")).isFalse();
        assertThat(guard.checkAndRecord("nonce-2")).isTrue();
    }

    @Test
    void blankNoncesAreNeverAccepted() {
        SandboxReplayGuard guard = new SandboxReplayGuard();

        assertThat(guard.checkAndRecord(null)).isFalse();
        assertThat(guard.checkAndRecord("")).isFalse();
        assertThat(guard.checkAndRecord("   ")).isFalse();
    }

    @Test
    void entriesAreEvictedOnceTheirTtlPasses() {
        AtomicLong now = new AtomicLong(0);
        SandboxReplayGuard guard = new SandboxReplayGuard(Duration.ofMinutes(30), 1000, now::get);

        assertThat(guard.checkAndRecord("nonce")).isTrue();
        assertThat(guard.checkAndRecord("nonce")).isFalse();

        // TTL 内仍然拦截
        now.addAndGet(Duration.ofMinutes(29).toMillis());
        assertThat(guard.checkAndRecord("nonce")).isFalse();

        // 超过 TTL:记录被淘汰,占用不再增长
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertThat(guard.size()).isZero();
        assertThat(guard.checkAndRecord("nonce")).isTrue();
    }

    @Test
    void memoryDoesNotGrowWithoutBoundAsNoncesExpire() {
        AtomicLong now = new AtomicLong(0);
        SandboxReplayGuard guard = new SandboxReplayGuard(Duration.ofMinutes(1), 100_000, now::get);

        // 模拟长期运行:每分钟一个新 nonce,旧的应被淘汰而不是永久累积
        for (int i = 0; i < 500; i++) {
            guard.checkAndRecord("nonce-" + i);
            now.addAndGet(Duration.ofMinutes(1).toMillis());
        }

        assertThat(guard.size())
                .as("过期记录必须被清掉,而不是随进程寿命只增不减")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void capacityCeilingIsEnforcedEvenWhenNothingHasExpired() {
        AtomicLong now = new AtomicLong(0);
        SandboxReplayGuard guard = new SandboxReplayGuard(Duration.ofHours(1), 50, now::get);

        for (int i = 0; i < 500; i++) {
            guard.checkAndRecord("nonce-" + i);
        }

        assertThat(guard.size())
                .as("即使都没过期,也不能突破容量上限")
                .isLessThanOrEqualTo(50);
    }

    @Test
    void concurrentCallersSeeExactlyOneAcceptancePerNonce() throws Exception {
        SandboxReplayGuard guard = new SandboxReplayGuard();
        int threads = 16;
        int noncesPerThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<String, AtomicInteger> accepted = new ConcurrentHashMap<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < noncesPerThread; i++) {
                    String nonce = "shared-" + i;
                    if (guard.checkAndRecord(nonce)) {
                        accepted.computeIfAbsent(nonce, k -> new AtomicInteger()).incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted).hasSize(noncesPerThread);
        assertThat(accepted.values()).allSatisfy(count ->
                assertThat(count.get())
                        .as("每个 nonce 在并发下只能被接受一次")
                        .isEqualTo(1));
    }
}
