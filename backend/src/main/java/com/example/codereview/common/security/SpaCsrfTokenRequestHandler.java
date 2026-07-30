package com.example.codereview.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * 单页应用的 CSRF 令牌处理:令牌以非 HttpOnly 的 {@code XSRF-TOKEN} Cookie 下发,
 * 前端读出后放进 {@code X-XSRF-TOKEN} 请求头回传。
 *
 * <p>两种取值方式必须区别对待,这也是本类存在的唯一理由:
 * <ul>
 *   <li><b>下发时</b>始终走 XOR 掩码。令牌若被渲染进响应体,固定值 + 压缩会构成 BREACH 侧信道,
 *       每次请求变换掩码可消除这一点;</li>
 *   <li><b>回传时</b>,请求头里的值是前端**从 Cookie 原样读出**的裸令牌,必须按裸值比对;
 *       只有表单参数里的值才是带掩码的。用错一种,合法请求会被判成 403。</li>
 * </ul>
 *
 * <p>参见 Spring Security 参考文档 "Single-Page Applications" 一节给出的同名范式。
 */
public final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken); // 裸值(来自 Cookie)
        }
        return xor.resolveCsrfTokenValue(request, csrfToken);       // 掩码值(来自表单参数)
    }
}
