package com.forgepilot.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * 限流器的三条承重性质：配额本身、窗口会翻转（否则第一个窗口用完就永久拒绝），
 * 以及键空间被打满时<strong>放过而不是拒绝</strong>——写反了这道限流就从防护
 * 变成了拒绝服务的放大器。
 */
class RateLimiterTest {

    /** 可推进的时钟。窗口翻转是这个类的核心行为，必须能在测试里控制时间。 */
    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long by) {
            millis += by;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void allowsUpToThePermitCountAndRefusesBeyondIt() {
        RateLimiter limiter = new RateLimiter(new MutableClock(0L), 3, 60_000L, 100);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).as("第四次超出配额").isFalse();
        assertThat(limiter.tryAcquire("a")).as("超出之后持续拒绝").isFalse();
    }

    /**
     * 窗口必须真的翻转。没有这条，第一个窗口用满之后该 key 就<strong>永久</strong>被拒——
     * 一个正常用户输错几次口令就再也登不上了。
     */
    @Test
    void aNewWindowRestoresTheFullQuota() {
        MutableClock clock = new MutableClock(0L);
        RateLimiter limiter = new RateLimiter(clock, 1, 60_000L, 100);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        clock.advance(59_999L);
        assertThat(limiter.tryAcquire("a")).as("窗口尚未结束").isFalse();

        clock.advance(1L);
        assertThat(limiter.tryAcquire("a")).as("新窗口，配额恢复").isTrue();
    }

    /**
     * 键空间被打满时<strong>fail-open</strong>。
     *
     * <p>反过来写——对新 key 一律拒绝——会让这道限流可以被利用：攻击者用大量伪造地址
     * 把表填满，此后每一个正常用户都被拒。那是把防护措施变成了放大器。
     * 所以极端情况下宁可退化成不限流。
     */
    @Test
    void failsOpenOnceTheKeySpaceIsFull() {
        RateLimiter limiter = new RateLimiter(new MutableClock(0L), 1, 60_000L, 2);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("b")).isTrue();

        // 表已达上限，"c" 是新 key：放过。
        assertThat(limiter.tryAcquire("c")).as("新 key 在表满时被放过").isTrue();
        assertThat(limiter.tryAcquire("c")).as("它始终不被跟踪，因此一直放过").isTrue();

        // 已在表内的 key 仍然照常受限——fail-open 只对新 key 生效。
        assertThat(limiter.tryAcquire("a")).as("已跟踪的 key 不因表满而豁免").isFalse();
    }
}
