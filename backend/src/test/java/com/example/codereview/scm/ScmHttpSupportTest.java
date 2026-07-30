package com.example.codereview.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 回写地址来自安装记录,一旦被写成内网地址,平台就会带着凭据去打自己的内网(P1-02)。
 * 这里覆盖的是「HTTPS 但仍指向内网」这一类绕过,以及重定向绕过。
 */
class ScmHttpSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ScmHttpSupport support(boolean allowInsecureLocalhost) {
        return new ScmHttpSupport(mapper, allowInsecureLocalhost);
    }

    private void assertRejected(ScmHttpSupport http, String baseUrl) {
        assertThatThrownBy(() -> http.postJson(baseUrl, "/repos/a/b/check-runs", "Authorization", "Bearer t", Map.of()))
                .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 协议是 https,主机却在内网——只校验协议会全部放行
            "https://10.0.0.5/api/v3",
            "https://172.16.0.1/api/v3",
            "https://192.168.1.10/api/v4",
            "https://169.254.169.254/latest/meta-data",
            "https://[fd00::1]/api/v4",
            "https://100.64.0.1/api/v4",
            // 不依赖 DNS 的主机名黑名单
            "https://metadata.google.internal/computeMetadata/v1",
            "https://gitlab.internal/api/v4",
            "https://gitlab.local/api/v4",
            // 非常规 IP 写法(2130706433 == 127.0.0.1)
            "https://2130706433/api/v3",
            "https://0x7f000001/api/v3",
    })
    void rejectsHttpsBasesThatPointAtPrivateOrReservedAddresses(String baseUrl) {
        assertRejected(support(false), baseUrl);
    }

    @Test
    void rejectsLoopbackUnlessInsecureLocalhostIsEnabled() {
        assertRejected(support(false), "https://127.0.0.1:8443/api/v3");
        assertRejected(support(false), "http://localhost:8080/api/v4");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.github.com",                 // 明文
            "https://user:pw@api.github.com",        // 凭据混在地址里
            "https://api.github.com?x=1",            // 带查询串
            "https://api.github.com#frag",           // 带片段
    })
    void keepsRejectingMalformedOrInsecureBases(String baseUrl) {
        assertRejected(support(false), baseUrl);
    }

    /**
     * 允许本机时校验必须真的放行(否则联调环境会被误伤):这里连一个已关闭的端口,
     * 期望失败发生在「连接」而不是「校验」——即抛 IllegalStateException 而非 SecurityException。
     */
    @Test
    void allowsLoopbackWhenExplicitlyEnabled() {
        assertThatThrownBy(() -> support(true)
                .postJson("http://127.0.0.1:1/api/v4", "/projects", "PRIVATE-TOKEN", "t", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 可信主机把我们重定向到内网,是绕过基地址校验的经典手法:校验只看第一跳。
     * 因此必须显式不跟随重定向——这里断言 302 被原样返回,且服务端只收到一次请求。
     */
    @Test
    void doesNotFollowRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            int status = support(true).postJson(base, "/projects", "PRIVATE-TOKEN", "t", Map.of("body", "x"));
            assertThat(status).isEqualTo(302);
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    /** 路径段编码:斜杠必须转义,否则 group/sub/project 会跑到别的接口上去。 */
    @Test
    void encodesPathSegmentsWithoutLeakingSlashesOrPluses() {
        assertThat(ScmHttpSupport.encodePathSegment("group/sub/project")).isEqualTo("group%2Fsub%2Fproject");
        assertThat(ScmHttpSupport.encodePathSegment("a b")).isEqualTo("a%20b");
    }
}
