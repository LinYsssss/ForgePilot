package com.example.codereview.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private static final String TRUSTED = "127.0.0.1,172.16.0.0/12,10.0.0.0/8";

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            req.addHeader("X-Forwarded-For", forwardedFor);
        }
        return req;
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED);

        // 公网客户端直连并自称是别人 —— 必须按它的真实地址计数,否则每次换一个假 IP 就能换一个限流桶
        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4")))
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4, 5.6.7.8")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void trustsForwardedHeaderOnlyFromTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED);

        assertThat(resolver.resolve(request("172.18.0.10", "198.51.100.7")))
                .isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("127.0.0.1", "198.51.100.7")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void picksRightmostNonProxyHopSoAClientCannotPrependAFakeOne() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED);

        // 客户端自带 "1.2.3.4",代理把真实地址追加在右侧;取右起第一个非受信地址
        assertThat(resolver.resolve(request("172.18.0.10", "1.2.3.4, 198.51.100.7")))
                .isEqualTo("198.51.100.7");
        // 链路末尾是内网代理,应继续向左跳过
        assertThat(resolver.resolve(request("172.18.0.10", "198.51.100.7, 10.0.0.5")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderMissingOrAllTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED);

        assertThat(resolver.resolve(request("172.18.0.10", null))).isEqualTo("172.18.0.10");
        assertThat(resolver.resolve(request("172.18.0.10", "   "))).isEqualTo("172.18.0.10");
        assertThat(resolver.resolve(request("172.18.0.10", "10.0.0.5, 172.16.0.9")))
                .isEqualTo("172.18.0.10");
    }

    @Test
    void emptyTrustListMeansNoHeaderIsEverTrusted() {
        ClientIpResolver resolver = new ClientIpResolver("");

        assertThat(resolver.resolve(request("172.18.0.10", "198.51.100.7")))
                .isEqualTo("172.18.0.10");
    }

    @Test
    void cidrBoundariesAreRespected() {
        ClientIpResolver resolver = new ClientIpResolver("172.16.0.0/12");

        assertThat(resolver.isTrusted("172.16.0.1")).isTrue();
        assertThat(resolver.isTrusted("172.31.255.254")).isTrue();
        assertThat(resolver.isTrusted("172.32.0.1")).isFalse();   // 刚好越界
        assertThat(resolver.isTrusted("172.15.255.254")).isFalse();
        assertThat(resolver.isTrusted("not-an-ip")).isFalse();
    }
}
