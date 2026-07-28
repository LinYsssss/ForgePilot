package com.example.codereview.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.common.exception.BusinessException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundUrlPolicyTest {

    private void assertRejected(String url) {
        assertThatThrownBy(() -> OutboundUrlPolicy.requirePublicHttpUrl(url, "仓库地址", false))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x",
            "http://localhost:8080/actuator",
            "https://127.0.0.1:9090/",
            "http://[::1]/x",
            // 十进制 / 十六进制 / 八进制表示的 127.0.0.1
            "http://2130706433/x",
            "http://0x7f000001/x",
            "http://017700000001/x",
            // 云元数据与链路本地
            "http://169.254.169.254/latest/meta-data/",
            "http://[fe80::1]/x",
            // 私有网段
            "http://10.0.0.5/x",
            "http://172.16.0.1/x",
            "http://192.168.1.1/x",
            // 唯一本地 IPv6 / CGNAT / 保留段
            "http://[fd00::1]/x",
            "http://100.64.0.1/x",
            "http://0.0.0.0/x",
            "http://198.18.0.1/x",
            "http://240.0.0.1/x",
    })
    void rejectsLoopbackPrivateLinkLocalAndAlternateNotations(String url) {
        assertRejected(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/repo.git",
            "file:///etc/passwd",
            "gopher://example.com/",
            "ssh://git@example.com/a.git",
    })
    void rejectsNonHttpSchemes(String url) {
        assertRejected(url);
    }

    @Test
    void rejectsBlankAndLocalHostnamesWithoutRelyingOnDns() {
        assertRejected("");
        assertRejected("   ");
        // 不依赖 DNS 的主机名黑名单
        assertRejected("http://localhost/x");
        assertRejected("http://foo.localhost/x");
        assertRejected("http://printer.local/x");
        assertRejected("http://metadata.google.internal/computeMetadata/v1/");
        assertRejected("http://svc.internal/x");
    }

    @Test
    void unresolvableHostIsNotRejectedOnThatBasisAlone() {
        // 解析失败不构成危险,且把它判为非法会让 DNS 抖动误伤正常仓库地址
        assertThatCode(() -> OutboundUrlPolicy.requirePublicHttpUrl(
                "https://this-host-should-not-exist.invalid/x", "仓库地址", false))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyEnabled() {
        assertRejected("http://127.0.0.1/x");
        assertThatCode(() -> OutboundUrlPolicy.requirePublicHttpUrl("http://127.0.0.1/x", "仓库地址", true))
                .doesNotThrowAnyException();
    }

    @Test
    void loopbackFlagDoesNotUnblockPrivateOrLinkLocal() throws Exception {
        // 放行本机不等于放行整个内网:云元数据地址在任何模式下都必须拒绝
        assertThat(OutboundUrlPolicy.isBlocked(InetAddress.getByName("169.254.169.254"), true)).isTrue();
        assertThat(OutboundUrlPolicy.isBlocked(InetAddress.getByName("10.0.0.1"), true)).isTrue();
        assertThat(OutboundUrlPolicy.isBlocked(InetAddress.getByName("192.168.0.1"), true)).isTrue();
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        assertThat(OutboundUrlPolicy.isBlocked(InetAddress.getByName("8.8.8.8"), false)).isFalse();
        assertThat(OutboundUrlPolicy.isBlocked(InetAddress.getByName("140.82.121.4"), false)).isFalse(); // github
    }
}
