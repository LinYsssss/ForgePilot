package com.forgepilot.common;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内的固定窗口限流计数器，按调用方给出的 key 计数。
 *
 * <p>固定窗口而非滑动窗口：交界处最多放过两倍配额，而攻击者拿到 40 次而不是 20 次
 * 仍远达不到可用的破解速率；滑动窗口要为每个 key 存一串时间戳，在无上界的 IP 空间上
 * 是内存风险。内存由此有两道界：窗口翻转时整张表被换掉，且 {@code maxKeys} 封顶。
 *
 * <p>封顶后 <strong>fail-open</strong>——放过而不是拒绝。反过来写，攻击者用伪造地址
 * 打满表就能让所有人被拒，这道限流本身就成了放大器。
 *
 * <p>本类不负责取 key；见 {@link RateLimitFilter}。
 */
public class RateLimiter {

    private final Clock clock;
    private final int permits;
    private final long windowMillis;
    private final int maxKeys;
    private final AtomicReference<Window> current;

    /**
     * @param permits      单个窗口内允许同一个 key 通过的次数
     * @param windowMillis 窗口长度
     * @param maxKeys      单个窗口内最多跟踪多少个不同 key，超出后对新 key fail-open
     */
    public RateLimiter(Clock clock, int permits, long windowMillis, int maxKeys) {
        if (permits < 1 || windowMillis < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("permits, windowMillis and maxKeys must all be positive.");
        }
        this.clock = clock;
        this.permits = permits;
        this.windowMillis = windowMillis;
        this.maxKeys = maxKeys;
        this.current = new AtomicReference<>(new Window(clock.millis(), new ConcurrentHashMap<>()));
    }

    /**
     * 记一次请求，并回答它是否在配额内。
     *
     * <p>窗口翻转用 CAS 完成：多个线程同时发现窗口过期时只有一个能装上新表，
     * 其余线程会读到那张已装上的新表，而不是各自把自己的表装上去、互相覆盖计数。
     */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        Window window = current.get();
        if (now - window.startMillis() >= windowMillis) {
            Window fresh = new Window(now, new ConcurrentHashMap<>());
            window = current.compareAndSet(window, fresh) ? fresh : current.get();
        }
        ConcurrentHashMap<String, AtomicInteger> hits = window.hits();
        if (hits.size() >= maxKeys && !hits.containsKey(key)) {
            return true;
        }
        return hits.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet() <= permits;
    }

    /** 一个窗口：它的起点，以及这个窗口内每个 key 的计数。窗口过期即整体丢弃。 */
    private record Window(long startMillis, ConcurrentHashMap<String, AtomicInteger> hits) {
    }
}
