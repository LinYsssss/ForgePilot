package com.example.codereview.scm;

import com.example.codereview.git.OutboundUrlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ScmHttpSupport {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final boolean allowInsecureLocalhost;

    public ScmHttpSupport(ObjectMapper mapper, boolean allowInsecureLocalhost) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // 显式拒绝跟随重定向:否则一个可信主机可以把我们重定向到内网,
                // 绕过下面对基地址做的全部校验。(这也是 Java 的默认值,写出来是为了防止被改。)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapper = mapper;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    public int postJson(String baseUrl, String path, String credentialHeader, String credential, Object body) {
        URI uri = safeUri(baseUrl, path);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(credentialHeader, credential)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception ex) {
            throw new IllegalStateException("SCM publication request failed", ex);
        }
    }

    public static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI safeUri(String baseUrl, String path) {
        URI base = URI.create(baseUrl);
        boolean localHttp = "http".equalsIgnoreCase(base.getScheme())
                && ("localhost".equalsIgnoreCase(base.getHost()) || "127.0.0.1".equals(base.getHost()));
        if (!"https".equalsIgnoreCase(base.getScheme()) && !(allowInsecureLocalhost && localHttp)) {
            throw new SecurityException("SCM API base must use HTTPS");
        }
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new SecurityException("SCM API base is invalid");
        }
        // 仅有 HTTPS 是不够的:https://10.0.0.5/ 或内网域名同样合法。回写地址来自安装记录,
        // 一旦被写成内网地址,平台就会带着凭据去打自己的内网。复用与仓库地址相同的出站策略。
        try {
            OutboundUrlPolicy.requirePublicHttpUrl(baseUrl, "SCM API 地址", allowInsecureLocalhost);
        } catch (RuntimeException rejected) {
            throw new SecurityException("SCM API base is not an allowed outbound target", rejected);
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }
}
