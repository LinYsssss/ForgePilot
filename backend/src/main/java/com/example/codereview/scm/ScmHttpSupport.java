package com.example.codereview.scm;

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
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }
}
