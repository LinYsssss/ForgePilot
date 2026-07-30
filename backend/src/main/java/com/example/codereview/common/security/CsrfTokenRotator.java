package com.example.codereview.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * 登录与退出时的 CSRF 令牌轮换。
 *
 * <p>为什么要换:登录前的令牌是匿名访客拿到的。如果攻击者能诱导受害者在自己的浏览器里
 * 先取得一个令牌、再完成登录,那么这枚攻击者已知的令牌在登录后依然有效——CSRF 防护形同虚设。
 * 登录成功即换新、退出即作废,把令牌的生命周期与会话对齐。
 *
 * <p>开关关闭时(默认)全部退化为空操作,不写任何 Cookie,以免在未启用 CSRF 的部署里
 * 留下一个永远不被校验的 Cookie 误导排查。
 */
@Component
public class CsrfTokenRotator {

    /** 与 {@link CookieCsrfTokenRepository} 的默认值一致,前端据此读取。 */
    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    private final boolean enabled;
    private final CsrfTokenRepository repository;

    public CsrfTokenRotator(@Value("${app.security.csrf.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 登录成功后调用:签发新令牌并覆盖 Cookie。 */
    public void rotate(HttpServletRequest request, HttpServletResponse response) {
        if (!enabled) {
            return;
        }
        CsrfToken fresh = repository.generateToken(request);
        repository.saveToken(fresh, request, response);
        // 同一请求内后续环节(如异常处理)读到的应当是新令牌,而不是刚被作废的那枚。
        request.setAttribute(CsrfToken.class.getName(), fresh);
    }

    /** 退出时调用:删除 Cookie,旧令牌立即失效。 */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        if (!enabled) {
            return;
        }
        repository.saveToken(null, request, response);
        request.removeAttribute(CsrfToken.class.getName());
    }
}
