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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** GitLab webhook -> authoritative API -> shared SCM/Review transaction. */
@SpringBootTest
class GitLabWebhookIngestionTest extends ScmTestBase {

    private static final String WEBHOOK = "/api/scm/gitlab/webhook";

    /** 本类专用的源地址：限流按源地址计数，各测试类分开才不会互相耗尽配额。 */
    private static final RequestPostProcessor FROM_PROVIDER = raw -> {
        raw.setRemoteAddr("192.0.2.241");
        return raw;
    };
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String NEXT_HEAD_SHA = "cccccccccccccccccccccccccccccccccccccccc";
    private static final Instant UPDATED_AT = Instant.parse("2026-08-22T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-08-22T11:00:00Z");

    private static StubGitLab gitlab;

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
    static void startGitLab() throws IOException {
        gitlab = new StubGitLab();
    }

    @AfterAll
    static void stopGitLab() {
        gitlab.stop();
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        gitlab.reset();
    }

    @Test
    void aVerifiedDeliveryStoresPaginatedAuthoritativeStateAndOpensTheSharedReview() throws Exception {
        Fixture fixture = new Fixture(false);
        gitlab.snapshot(BASE_SHA, HEAD_SHA, "feat/REQ-" + fixture.requirement + "-gitlab", UPDATED_AT);
        gitlab.diffPages(fullDiffPage(), """
                [{"old_path":"large.bin","new_path":"large.bin","diff":"ignored",
                  "new_file":false,"deleted_file":false,"renamed_file":false,
                  "collapsed":false,"too_large":true}]""");

        deliverLegacy(fixture, 17, "Merge Request Hook", status().isAccepted());

        assertThat(gitlab.requestLines()).containsExactly(
                "/api/v4/projects/" + fixture.externalId + "/merge_requests/17",
                "/api/v4/projects/" + fixture.externalId + "/merge_requests/17/versions?per_page=100&page=1",
                "/api/v4/projects/" + fixture.externalId + "/merge_requests/17/diffs?per_page=100&page=1",
                "/api/v4/projects/" + fixture.externalId + "/merge_requests/17/diffs?per_page=100&page=2",
                "/api/v4/projects/" + fixture.externalId + "/merge_requests/17");
        assertThat(gitlab.privateToken()).isEqualTo("token-" + fixture.externalId);

        long pullRequest = pullRequestId(fixture, 17);
        assertThat(jdbc.queryForMap("select base_sha, head_sha, title, source_revision, "
                + "author_external_user_id, author_username, requirement_id "
                + "from pull_request where id = ?", pullRequest))
                .containsEntry("base_sha", BASE_SHA)
                .containsEntry("head_sha", HEAD_SHA)
                .containsEntry("title", "A merge request")
                .containsEntry("source_revision", "901")
                .containsEntry("author_external_user_id", "4242")
                .containsEntry("author_username", "gitlab-user")
                .containsEntry("requirement_id", fixture.requirement);
        assertThat(jdbc.queryForObject("select jsonb_array_length(changed_files) from pull_request where id = ?",
                Integer.class, pullRequest)).isEqualTo(101);
        assertThat(jdbc.queryForObject("select file->>'patch' from pull_request pr "
                + "cross join lateral jsonb_array_elements(pr.changed_files) file "
                + "where pr.id = ? and file->>'path' = 'large.bin'",
                String.class, pullRequest)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from review where pull_request_id = ?",
                Integer.class, pullRequest)).isOne();
    }

    @Test
    void aStandardWebhookAuthenticatesTheExactRawBodyAndCannotDowngrade() throws Exception {
        Fixture fixture = new Fixture(true);
        String body = delivery(fixture, 17);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String messageId = "msg-" + SEQUENCE.incrementAndGet();
        String signature = standardSign(fixture.secret, messageId, timestamp, body);
        String reserialized = body.replace("{\"object_kind\"", "{ \"object_kind\"");

        deliverStandard(reserialized, fixture.secret, signature, messageId, timestamp,
                status().isUnauthorized());
        assertThat(gitlab.requests()).isZero();

        deliverStandard(body, fixture.secret, signature, messageId, timestamp, status().isAccepted());
        assertThat(pullRequestCount(fixture)).isOne();

        // Standard headers were present, so even the exact legacy token cannot
        // rescue a bad signature.
        mockMvc.perform(MockMvcRequestBuilders.post(WEBHOOK).with(FROM_PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .header("X-Gitlab-Token", fixture.secret)
                        .header("webhook-id", "different")
                        .header("webhook-timestamp", timestamp)
                        .header("webhook-signature", signature))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownMalformedAndBadTokenDeliveriesAreIndistinguishableAndFetchNothing() throws Exception {
        Fixture fixture = new Fixture(false);
        String body = delivery(fixture, 17);
        MvcResult badToken = deliverLegacy(body, fixture.secret + "x", "Merge Request Hook",
                status().isUnauthorized());
        String unknownBody = body.replace(fixture.externalId, "never-registered");
        MvcResult unknown = deliverLegacy(unknownBody, fixture.secret, "Merge Request Hook",
                status().isUnauthorized());
        MvcResult malformed = deliverLegacy("{not-json", fixture.secret, "Merge Request Hook",
                status().isUnauthorized());

        assertThat(errorIdentity(badToken)).isEqualTo(errorIdentity(unknown)).isEqualTo(errorIdentity(malformed));
        assertThat(gitlab.requests()).isZero();
        assertThat(pullRequestCount(fixture)).isZero();
    }

    @Test
    void replayIsIdempotentAndAnOlderAuthoritativeSnapshotCannotRollBackTheRow() throws Exception {
        Fixture fixture = new Fixture(false);
        gitlab.snapshot(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);
        String body = delivery(fixture, 17);

        deliverLegacy(body, fixture.secret, "Merge Request Hook", status().isAccepted());
        long pullRequest = pullRequestId(fixture, 17);
        java.util.Map<String, Object> current = snapshot(pullRequest);
        deliverLegacy(body, fixture.secret, "Merge Request Hook", status().isAccepted());
        assertThat(jdbc.queryForObject("select count(*) from review where pull_request_id = ?",
                Integer.class, pullRequest)).isOne();

        gitlab.snapshot("0000000000000000000000000000000000000000", NEXT_HEAD_SHA,
                "feat/other", EARLIER);
        deliverLegacy(body, fixture.secret, "Merge Request Hook", status().isAccepted());

        assertThat(snapshot(pullRequest)).isEqualTo(current);
        assertThat(pullRequestCount(fixture)).isOne();
    }

    @Test
    void aVerifiedNonMergeRequestEventIsAnAcknowledgedNoOp() throws Exception {
        Fixture fixture = new Fixture(false);

        deliverLegacy(fixture, 17, "Push Hook", status().isAccepted());

        assertThat(gitlab.requests()).isZero();
        assertThat(pullRequestCount(fixture)).isZero();
    }

    @Test
    void malformedAndRateLimitedProviderResponsesNeverWriteAPartialSnapshot() throws Exception {
        Fixture malformed = new Fixture(false);
        gitlab.diffPages("""
                [{"old_path":"src/a.txt","new_path":"src/a.txt","diff":"@@",
                  "new_file":false,"deleted_file":false}]""");

        MvcResult invalid = deliverLegacy(malformed, 17, "Merge Request Hook",
                status().isUnprocessableEntity());
        assertThat(json.readTree(invalid.getResponse().getContentAsString()).path("message").asString())
                .contains("renamed_file");
        assertThat(pullRequestCount(malformed)).isZero();

        Fixture limited = new Fixture(false);
        gitlab.reset();
        gitlab.mergeRequestStatus(429);
        MvcResult unavailable = deliverLegacy(limited, 17, "Merge Request Hook",
                status().isServiceUnavailable());
        assertThat(json.readTree(unavailable.getResponse().getContentAsString()).path("code").asString())
                .isEqualTo("provider_unavailable");
        assertThat(pullRequestCount(limited)).isZero();
    }

    @Test
    void anOversizedGitLabDiffIsRejectedWithoutWritingAnything() throws Exception {
        Fixture fixture = new Fixture(false);
        String huge = "x".repeat(ChangedFile.MAX_TOTAL_CHARS + 1);
        gitlab.diffPages("""
                [{"old_path":"huge.txt","new_path":"huge.txt","diff":"%s",
                  "new_file":false,"deleted_file":false,"renamed_file":false}]""".formatted(huge));

        deliverLegacy(fixture, 17, "Merge Request Hook", status().isUnprocessableEntity());

        assertThat(pullRequestCount(fixture)).isZero();
    }

    private java.util.Map<String, Object> snapshot(long pullRequest) {
        return jdbc.queryForMap("select base_sha, head_sha, review_input_fingerprint, source_revision, "
                + "source_updated_at, changed_files::text from pull_request where id = ?", pullRequest);
    }

    private long pullRequestId(Fixture fixture, int iid) {
        return jdbc.queryForObject("select id from pull_request where repository_id = ? and external_number = ?",
                Long.class, fixture.repository, iid);
    }

    private int pullRequestCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository);
    }

    private String errorIdentity(MvcResult result) throws Exception {
        JsonNode error = json.readTree(result.getResponse().getContentAsString());
        return error.path("code").asString() + "|" + error.path("message").asString();
    }

    private MvcResult deliverLegacy(Fixture fixture, int iid, String event, ResultMatcher expected)
            throws Exception {
        return deliverLegacy(delivery(fixture, iid), fixture.secret, event, expected);
    }

    private MvcResult deliverLegacy(String body, String secret, String event, ResultMatcher expected)
            throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(WEBHOOK).with(FROM_PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Gitlab-Event", event).header("X-Gitlab-Token", secret))
                .andExpect(expected).andReturn();
    }

    private MvcResult deliverStandard(String body, String secret, String signature,
            String messageId, String timestamp, ResultMatcher expected) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(WEBHOOK).with(FROM_PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .header("webhook-id", messageId).header("webhook-timestamp", timestamp)
                        .header("webhook-signature", signature))
                .andExpect(expected).andReturn();
    }

    private String delivery(Fixture fixture, int iid) {
        return """
                {"object_kind":"merge_request","event_type":"merge_request",
                 "project":{"id":%s,"web_url":"%s/group/repository"},
                 "object_attributes":{"iid":%d,"action":"update"}}"""
                .formatted(fixture.externalId, gitlab.instanceUrl(), iid);
    }

    private static String standardSign(String token, String messageId, String timestamp, String body) {
        try {
            byte[] key = Base64.getDecoder().decode(token.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((messageId + "." + timestamp + ".").getBytes(UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String fullDiffPage() {
        return java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> """
                        {"old_path":"src/%03d.txt","new_path":"src/%03d.txt","diff":"@@",
                         "new_file":false,"deleted_file":false,"renamed_file":false}"""
                        .formatted(index, index))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private final class Fixture {

        private final long project;
        private final long requirement;
        private final long repository;
        private final String externalId;
        private final String secret;

        private Fixture(boolean standardSigning) {
            int sequence = SEQUENCE.incrementAndGet();
            long owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "gitlab-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "gitlab-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.requirement = jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                    Long.class, project);
            long revision = jdbc.queryForObject("""
                    insert into requirement_revision
                        (project_id, requirement_id, seq, title, background, description, created_by)
                    values (?, ?, 1, 'GitLab requirement', '', '', ?) returning id
                    """, Long.class, project, requirement, owner);
            jdbc.update("update requirement set current_revision_id = ? where id = ?", revision, requirement);
            jdbc.update("insert into acceptance_criterion "
                    + "(project_id, requirement_revision_id, ac_key, sort_order, text) "
                    + "values (?, ?, 'AC-0001', 0, 'The GitLab path works')", project, revision);

            this.externalId = Integer.toString(90000 + sequence);
            if (standardSigning) {
                byte[] key = ("gitlab-signing-key-" + sequence).getBytes(UTF_8);
                this.secret = "whsec_" + Base64.getEncoder().encodeToString(key);
            } else {
                this.secret = "gitlab-legacy-secret-" + sequence;
            }
            this.repository = jdbc.queryForObject("""
                    insert into scm_repository
                        (project_id, provider, instance_identity, external_id,
                         api_base, encrypted_token, encrypted_secret)
                    values (?, 'GITLAB', ?, ?, ?, ?, ?) returning id
                    """, Long.class, project, InstanceIdentity.of(URI.create(gitlab.apiBase())), externalId,
                    gitlab.apiBase(), cipher.encrypt("token-" + externalId), cipher.encrypt(secret));
        }
    }

    private static final class StubGitLab {

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> requestLines = Collections.synchronizedList(new ArrayList<>());
        private volatile String privateToken;
        private volatile String mergeRequest;
        private volatile String versions;
        private volatile List<String> diffPages;
        private volatile int mergeRequestStatus;

        private StubGitLab() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            reset();
        }

        private String instanceUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String apiBase() {
            return instanceUrl() + "/api/v4";
        }

        private void reset() {
            requests.set(0);
            requestLines.clear();
            privateToken = null;
            mergeRequestStatus = 200;
            diffPages = List.of("""
                    [{"old_path":"src/a.txt","new_path":"src/a.txt","diff":"@@ -1 +1 @@\\n-a\\n+b",
                      "new_file":false,"deleted_file":false,"renamed_file":false}]""");
            snapshot(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);
        }

        private void snapshot(String base, String head, String branch, Instant updatedAt) {
            mergeRequest = """
                    {"iid":17,"title":"A merge request","source_branch":"%s","updated_at":"%s",
                     "diff_refs":{"base_sha":"%s","head_sha":"%s","start_sha":"%s"},
                     "author":{"id":4242,"username":"gitlab-user"}}"""
                    .formatted(branch, updatedAt, base, head, base);
            versions = """
                    [{"id":901,"base_commit_sha":"%s","head_commit_sha":"%s",
                      "start_commit_sha":"%s","state":"collected"}]"""
                    .formatted(base, head, base);
        }

        private void diffPages(String... pages) {
            diffPages = List.of(pages);
        }

        private void mergeRequestStatus(int status) {
            mergeRequestStatus = status;
        }

        private int requests() {
            return requests.get();
        }

        private List<String> requestLines() {
            return List.copyOf(requestLines);
        }

        private String privateToken() {
            return privateToken;
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            URI uri = exchange.getRequestURI();
            requestLines.add(uri.toString());
            privateToken = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");

            if (uri.getPath().matches(".*/merge_requests/17$") && mergeRequestStatus != 200) {
                respond(exchange, mergeRequestStatus, "{\"message\":\"limited\"}");
                return;
            }
            String body;
            if (uri.getPath().endsWith("/versions")) {
                body = versions;
            } else if (uri.getPath().endsWith("/diffs")) {
                int page = Integer.parseInt(uri.getQuery().replaceAll(".*page=", ""));
                body = page <= diffPages.size() ? diffPages.get(page - 1) : "[]";
            } else {
                body = mergeRequest;
            }
            respond(exchange, 200, body);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }

        private void stop() {
            server.stop(0);
        }
    }
}
