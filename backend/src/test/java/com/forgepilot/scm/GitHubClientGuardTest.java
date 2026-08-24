package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The refusal branch of {@code GitHubClient.required(...)}, which batch 2's
 * {@code result.md} 7.5 recorded as never asserted.
 *
 * <p>Thirteen ingestion tests proved the guard <em>runs</em> and accepts a
 * well-formed payload. That is not the same property. Batch 2 shipped, briefly, a
 * version that looked up a field literally named {@code "base.sha"} inside
 * {@code base}: the guard ran, refused everything, and the happy path caught it.
 * The reverse mistake — a guard that runs and refuses nothing — no happy-path test
 * can catch, and {@code author_external_user_id} is where it would hurt, because
 * D010 makes it the authorization key and {@code JsonNode.asString()} answers ""
 * for a node that is not there. Every ghost author would then share one identity
 * that passes NOT NULL.
 *
 * <p>So each case here removes exactly one authoritative field, asserts 422 with
 * <em>that field's</em> name in the message — not merely some refusal — and
 * asserts the delivery wrote nothing. {@link #aWellFormedPayloadIsStillAccepted()}
 * is the control that keeps all of it from being vacuous, and the
 * {@code base.sha}-as-a-field-name case pins the historical bug from the other
 * side.
 */
@SpringBootTest
class GitHubClientGuardTest extends ScmTestBase {

    private static final String WEBHOOK = "/api/scm/github/webhook";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String BASE_SHA = "1111111111111111111111111111111111111111";
    private static final String HEAD_SHA = "2222222222222222222222222222222222222222";
    private static final String FILES = """
            [{"filename":"src/a.txt","status":"modified","patch":"@@ -1 +1 @@\\n-a\\n+b\\n"}]""";

    private static StubProvider provider;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScmSecretCipher cipher;

    @Autowired
    private ObjectMapper json;

    private MockMvc mockMvc;

    @BeforeAll
    static void startProvider() throws IOException {
        provider = new StubProvider();
    }

    @AfterAll
    static void stopProvider() {
        provider.stop();
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        provider.serveFiles(FILES);
    }

    /**
     * Every authoritative field of the pull request, removed one at a time. The
     * whole {@code base} object disappearing and a field that is null, a container
     * or blank are the same guard reached by other routes: {@code path()} answers a
     * missing node for all of them, and {@code asString()} would answer "" for all
     * of them.
     */
    static Stream<Case> malformedPullRequests() {
        return Stream.of(
                new Case("base.sha is absent", pullRequest("""
                        "base":{},"head":{"sha":"%s","ref":"feat/x"},"user":{"id":424242,"login":"octocat"}"""
                        .formatted(HEAD_SHA)), "is missing base.sha"),
                new Case("the whole base object is absent", pullRequest("""
                        "head":{"sha":"%s","ref":"feat/x"},"user":{"id":424242,"login":"octocat"}"""
                        .formatted(HEAD_SHA)), "is missing base.sha"),
                new Case("base.sha is null", pullRequest("""
                        "base":{"sha":null},"head":{"sha":"%s","ref":"feat/x"},\
                        "user":{"id":424242,"login":"octocat"}""".formatted(HEAD_SHA)),
                        "is missing base.sha"),
                new Case("base.sha is a container", pullRequest("""
                        "base":{"sha":{"value":"%s"}},"head":{"sha":"%s","ref":"feat/x"},\
                        "user":{"id":424242,"login":"octocat"}""".formatted(BASE_SHA, HEAD_SHA)),
                        "is missing base.sha"),
                // The historical bug from the other side: a payload that carries a
                // field literally named "base.sha" and no "sha" must still be
                // refused. A guard that looked the label up as a field name would
                // accept this one and store the wrong value.
                new Case("base carries a field literally named base.sha", pullRequest("""
                        "base":{"base.sha":"%s"},"head":{"sha":"%s","ref":"feat/x"},\
                        "user":{"id":424242,"login":"octocat"}""".formatted(BASE_SHA, HEAD_SHA)),
                        "is missing base.sha"),
                new Case("base.sha is blank", pullRequest("""
                        "base":{"sha":"   "},"head":{"sha":"%s","ref":"feat/x"},\
                        "user":{"id":424242,"login":"octocat"}""".formatted(HEAD_SHA)),
                        "has an empty base.sha"),
                new Case("head.sha is absent", pullRequest("""
                        "base":{"sha":"%s"},"head":{"ref":"feat/x"},"user":{"id":424242,"login":"octocat"}"""
                        .formatted(BASE_SHA)), "is missing head.sha"),
                new Case("head.ref is absent", pullRequest("""
                        "base":{"sha":"%s"},"head":{"sha":"%s"},"user":{"id":424242,"login":"octocat"}"""
                        .formatted(BASE_SHA, HEAD_SHA)), "is missing head.ref"),
                new Case("user.id is absent", pullRequest("""
                        "base":{"sha":"%s"},"head":{"sha":"%s","ref":"feat/x"},"user":{"login":"octocat"}"""
                        .formatted(BASE_SHA, HEAD_SHA)), "is missing user.id"),
                new Case("user.id is null", pullRequest("""
                        "base":{"sha":"%s"},"head":{"sha":"%s","ref":"feat/x"},\
                        "user":{"id":null,"login":"octocat"}""".formatted(BASE_SHA, HEAD_SHA)),
                        "is missing user.id"),
                new Case("the whole user object is absent", pullRequest("""
                        "base":{"sha":"%s"},"head":{"sha":"%s","ref":"feat/x"}"""
                        .formatted(BASE_SHA, HEAD_SHA)), "is missing user.id"),
                new Case("user.login is absent", pullRequest("""
                        "base":{"sha":"%s"},"head":{"sha":"%s","ref":"feat/x"},"user":{"id":424242}"""
                        .formatted(BASE_SHA, HEAD_SHA)), "is missing user.login"),
                new Case("title is absent", """
                        {"number":7,"updated_at":"2026-08-21T12:00:00Z","base":{"sha":"%s"},\
                        "head":{"sha":"%s","ref":"feat/x"},"user":{"id":424242,"login":"octocat"}}"""
                        .formatted(BASE_SHA, HEAD_SHA), "is missing title"),
                new Case("updated_at is absent", """
                        {"number":7,"title":"A pull request","base":{"sha":"%s"},\
                        "head":{"sha":"%s","ref":"feat/x"},"user":{"id":424242,"login":"octocat"}}"""
                        .formatted(BASE_SHA, HEAD_SHA), "is missing updated_at"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedPullRequests")
    void anAuthoritativeFieldTheProviderDidNotSendIsRefusedByName(Case malformed) throws Exception {
        Fixture fixture = new Fixture();
        provider.servePullRequest(malformed.payload());

        MvcResult refused = deliver(fixture, 7, status().isUnprocessableEntity());

        assertThat(message(refused)).contains(malformed.expected());
        assertNothingWasWritten(fixture);
    }

    /**
     * The same guard on the file list, where a missing {@code filename} would be
     * stored as "" inside the manifest and then hashed into the fingerprint — a
     * review input that claims to describe a file with no name.
     */
    static Stream<Case> malformedFileLists() {
        return Stream.of(
                new Case("filename is absent", """
                        [{"status":"modified","patch":"@@"}]""", "is missing filename"),
                new Case("filename is null", """
                        [{"filename":null,"status":"modified","patch":"@@"}]""", "is missing filename"),
                new Case("filename is blank", """
                        [{"filename":"  ","status":"modified","patch":"@@"}]""", "has an empty filename"),
                new Case("status is absent", """
                        [{"filename":"src/a.txt","patch":"@@"}]""", "is missing status"),
                // Only the second page is malformed, so the refusal has to survive
                // pagination rather than only ever looking at the first response.
                new Case("a later page is malformed", null, "is missing filename"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFileLists")
    void aChangedFileTheProviderDescribedBadlyIsRefusedByName(Case malformed) throws Exception {
        Fixture fixture = new Fixture();
        provider.servePullRequest(wellFormed());
        if (malformed.payload() == null) {
            provider.serveFilePages(fullPage(), """
                    [{"status":"added","patch":"@@"}]""");
        } else {
            provider.serveFiles(malformed.payload());
        }

        MvcResult refused = deliver(fixture, 7, status().isUnprocessableEntity());

        assertThat(message(refused)).contains(malformed.expected());
        assertNothingWasWritten(fixture);
    }

    /**
     * The control. Without it every assertion above would still hold for a client
     * that refused everything — which is precisely the bug batch 2 actually shipped
     * and the happy path caught.
     */
    @Test
    void aWellFormedPayloadIsStillAccepted() throws Exception {
        Fixture fixture = new Fixture();
        provider.servePullRequest(wellFormed());

        deliver(fixture, 7, status().isAccepted());

        assertThat(jdbc.queryForMap("select base_sha, head_sha, author_external_user_id, author_username "
                + "from pull_request where repository_id = ?", fixture.repository))
                .containsEntry("base_sha", BASE_SHA)
                .containsEntry("head_sha", HEAD_SHA)
                .containsEntry("author_external_user_id", "424242")
                .containsEntry("author_username", "octocat");
    }

    // ------------------------------------------------------------------ helpers

    /** A rejection has to write nothing: 422 with a row stored would be worse than 500. */
    private void assertNothingWasWritten(Fixture fixture) {
        assertThat(jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pull_request_requirement_event "
                + "where project_id = ?", Integer.class, fixture.project)).isZero();
    }

    private String message(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo("unprocessable");
        return body.path("message").asString();
    }

    private static String wellFormed() {
        return pullRequest("""
                "base":{"sha":"%s"},"head":{"sha":"%s","ref":"feat/x"},\
                "user":{"id":424242,"login":"octocat"}""".formatted(BASE_SHA, HEAD_SHA));
    }

    /** The fields every case shares, so each case differs in exactly one thing. */
    private static String pullRequest(String rest) {
        return """
                {"number":7,"title":"A pull request","updated_at":"2026-08-21T12:00:00Z",%s}"""
                .formatted(rest);
    }

    /** 100 well-formed entries, which is what makes the client ask for a second page. */
    private static String fullPage() {
        return java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> """
                        {"filename":"src/%03d.txt","status":"modified","patch":"@@"}""".formatted(index))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private MvcResult deliver(Fixture fixture, int number, ResultMatcher expected) throws Exception {
        String body = """
                {"action":"synchronize","number":%d,
                 "repository":{"id":"%s","html_url":"%s/octo/repo"},
                 "pull_request":{"number":%d}}"""
                .formatted(number, fixture.externalId, provider.apiBase(), number);
        return mockMvc.perform(MockMvcRequestBuilders.post(WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Hub-Signature-256", sign(body, fixture.secret))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "delivery-" + SEQUENCE.incrementAndGet()))
                .andExpect(expected)
                .andReturn();
    }

    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** One case: a name for the report, the payload it serves, and what the refusal must say. */
    record Case(String name, String payload, String expected) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** A project with a LEADER and a repository pointed at the stub. */
    private final class Fixture {

        private final long project;
        private final long repository;
        private final String externalId;
        private final String secret;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            long owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "guard-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "guard-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.externalId = "guard-repo-" + sequence;
            this.secret = "guard-secret-" + sequence;
            this.repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) values (?, 'GITHUB', ?, ?, ?, ?, ?) "
                            + "returning id",
                    Long.class, project, InstanceIdentity.of(URI.create(provider.apiBase())), externalId,
                    provider.apiBase(), cipher.encrypt("token"), cipher.encrypt(secret));
        }
    }

    // ----------------------------------------------------------------- provider

    /** A real socket serving whatever this test tells it to, well formed or not. */
    private static final class StubProvider {

        private final HttpServer server;
        private volatile String pullRequestJson = "";
        private volatile List<String> filePages = List.of("[]");

        private StubProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void servePullRequest(String json) {
            pullRequestJson = json;
        }

        private void serveFiles(String json) {
            filePages = List.of(json);
        }

        private void serveFilePages(String... pages) {
            filePages = List.of(pages);
        }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String body;
            if (uri.getPath().endsWith("/files")) {
                int page = Integer.parseInt(uri.getQuery().replaceAll(".*page=", ""));
                body = page <= filePages.size() ? filePages.get(page - 1) : "[]";
            } else {
                body = pullRequestJson;
            }
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }

        private void stop() {
            server.stop(0);
        }
    }
}
