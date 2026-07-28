package com.example.codereview.auth;

import com.example.codereview.common.exception.BusinessException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按用户名维度的登录失败节流,与 {@code RateLimitFilter} 的 IP 维度互补。
 *
 * <p>只有 IP 维度是不够的:攻击者换出口 IP(代理池、僵尸网络)就能继续针对同一个账号猜口令,
 * 而每个 IP 的计数都很低。反过来只按用户名也不够——同一 IP 可以轮着撞很多账号。两个维度都要。
 *
 * <p>与限流器一致:进程内、固定窗口、无 Redis。单实例部署下足够,横向扩容需要换共享存储。
 */
@Component
public class LoginAttemptGuard {

    private final int maxFailures;
    private final long windowMs;
    private final Map<String, Window> failures = new ConcurrentHashMap<>();
    /** 防止被大量随机用户名撑爆内存。 */
    private static final int MAX_TRACKED = 10_000;

    public LoginAttemptGuard(
            @Value("${app.security.login.max-failures-per-username:10}") int maxFailures,
            @Value("${app.security.login.failure-window-seconds:600}") int windowSeconds) {
        this.maxFailures = maxFailures;
        this.windowMs = Math.max(1, windowSeconds) * 1000L;
    }

    /** 已被锁定则直接拒绝,不再去比对口令。 */
    public void assertNotLocked(String username) {
        if (maxFailures <= 0) {
            return;
        }
        Window window = failures.get(key(username));
        if (window == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (window) {
            if (now - window.start >= windowMs) {
                return; // 窗口已过期,视为清零(下次 record 时重建)
            }
            if (window.count >= maxFailures) {
                long retryAfter = Math.max(1, (windowMs - (now - window.start)) / 1000);
                throw new BusinessException(429,
                        "该账号登录失败次数过多，请在 " + retryAfter + " 秒后重试");
            }
        }
    }

    public void recordFailure(String username) {
        if (maxFailures <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (failures.size() > MAX_TRACKED) {
            failures.entrySet().removeIf(entry -> now - entry.getValue().start >= windowMs);
        }
        failures.compute(key(username), (k, existing) -> {
            if (existing == null || now - existing.start >= windowMs) {
                return new Window(now);
            }
            synchronized (existing) {
                existing.count++;
            }
            return existing;
        });
    }

    /** 登录成功即清除该账号的失败记录,避免正常用户被自己之前的手误拖累。 */
    public void recordSuccess(String username) {
        failures.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
            this.count = 1;
        }
    }
}
