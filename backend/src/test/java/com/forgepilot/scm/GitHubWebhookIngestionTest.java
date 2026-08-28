package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook path end to end, against a real socket and a real PostgreSQL, with
 * no credential anywhere: the provider is {@code com.sun.net.httpserver.HttpServer}
 * on loopback  and the repository's {@code api_base} column points at it,
 * which is the same column a self-hosted instance would use.
 */
@SpringBootTest
class GitHubWebhookIngestionTest extends ScmTestBase {

    private static final String WEBHOOK = "/api/scm/github/webhook";

    /** 本类专用的源地址：限流按源地址计数，各测试类分开才不会互相耗尽配额。 */
    private static final RequestPostProcessor FROM_PROVIDER = raw -> {
        raw.setRemoteAddr("192.0.2.240");
        return raw;
    };
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String BASE_SHA = "1111111111111111111111111111111111111111";
    private static final String HEAD_SHA = "2222222222222222222222222222222222222222";
    private static final String OTHER_HEAD_SHA = "3333333333333333333333333333333333333333";
    private static final Instant UPDATED_AT = Instant.parse("2026-08-21T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-08-21T11:00:00Z");
    private static final ChangedFile MODIFIED =
            new ChangedFile("src/a.txt", "modified", "@@ -1 +1 @@\n-a\n+b\n");
    private static final ChangedFile ADDED = new ChangedFile("src/B.txt", "added", null);

    private static StubProvider provider;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScmSecretCipher cipher;

    @Autowired
    private PullRequestSyncService sync;

    @Autowired
    private ScmRepositoryRepository repositories;

    @Autowired
    private RecordingListener listener;

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
        provider.reset();
        listener.reset();
    }

    @Test
    void aVerifiedDeliveryStoresWhatTheProviderSaysAndNotWhatThePayloadSays() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/REQ-" + fixture.requirement + "-login", UPDATED_AT);

        deliver(fixture, 7, "pull_request", status().isAccepted());

        // The client asked the provider by repository id, over the api_base column,
        // with the stored token. Without this the stub proves nothing about github.com.
        assertThat(provider.requestLines()).containsExactly(
                "/repositories/" + fixture.externalId + "/pulls/7",
                "/repositories/" + fixture.externalId + "/pulls/7/files?per_page=100&page=1");
        assertThat(provider.authorization()).isEqualTo("Bearer token-" + fixture.externalId);

        long pullRequest = pullRequestId(fixture, 7);
        assertThat(jdbc.queryForMap("select * from pull_request where id = ?", pullRequest))
                .containsEntry("base_sha", BASE_SHA)
                .containsEntry("head_sha", HEAD_SHA)
                .containsEntry("title", "A pull request")
                .containsEntry("author_external_user_id", "424242")
                .containsEntry("author_username", "octocat")
                .containsEntry("requirement_id", fixture.requirement)
                // Nothing computes this yet: project exposes no lookup by SCM identity.
                .containsEntry("author_user_id", null);

        // Equal to the pure function's value over exactly what the provider served,
        // including the file that carried no patch at all.
        assertThat(fingerprintOf(pullRequest)).isEqualTo(ReviewInputFingerprint.of("GITHUB",
                fixture.instanceIdentity, fixture.externalId,
                new PullRequestSnapshot(7, BASE_SHA, HEAD_SHA, "branch", "title", null, UPDATED_AT,
                        "424242", "octocat", List.of(MODIFIED, ADDED))));

        // Real JSONB, in canonical path-byte order, with absent still absent.
        assertThat(jdbc.queryForObject("select jsonb_array_length(changed_files) from pull_request "
                + "where id = ?", Integer.class, pullRequest)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select changed_files->0->>'path' from pull_request where id = ?",
                String.class, pullRequest)).isEqualTo("src/B.txt");
        assertThat(jdbc.queryForObject("select changed_files->0->>'patch' from pull_request where id = ?",
                String.class, pullRequest)).isNull();
        assertThat(jdbc.queryForObject("select changed_files->1->>'patch' from pull_request where id = ?",
                String.class, pullRequest)).isEqualTo(MODIFIED.patch());

        // One SYSTEM row for the automatic association, with no actor.
        assertThat(jdbc.queryForList("select actor_type, actor_user_id, from_requirement_id, "
                + "to_requirement_id from pull_request_requirement_event where pull_request_id = ?",
                pullRequest))
                .singleElement()
                .satisfies(event -> assertThat(event)
                        .containsEntry("actor_type", "SYSTEM")
                        .containsEntry("actor_user_id", null)
                        .containsEntry("from_requirement_id", null)
                        .containsEntry("to_requirement_id", fixture.requirement));
    }

    @Test
    void replayingTheSameDeliveryChangesNothing() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/REQ-" + fixture.requirement + "-login", UPDATED_AT);
        String body = delivery(fixture, 7);
        String signature = sign(body, fixture.secret);

        deliver(body, signature, "pull_request", status().isAccepted());
        long pullRequest = pullRequestId(fixture, 7);
        java.util.Map<String, Object> afterFirst = snapshotColumns(pullRequest);

        deliver(body, signature, "pull_request", status().isAccepted());
        deliver(body, signature, "pull_request", status().isAccepted());

        assertThat(jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository)).isEqualTo(1);
        assertThat(snapshotColumns(pullRequest)).isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject("select count(*) from pull_request_requirement_event "
                + "where pull_request_id = ?", Integer.class, pullRequest)).isEqualTo(1);
    }

    @Test
    void anOlderDeliveryNeverRollsTheSnapshotBackwards() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);
        deliver(fixture, 7, "pull_request", status().isAccepted());
        long pullRequest = pullRequestId(fixture, 7);
        java.util.Map<String, Object> current = snapshotColumns(pullRequest);

        // A retried delivery whose authoritative read is older than what is stored.
        provider.pullRequest("0000000000000000000000000000000000000000", OTHER_HEAD_SHA, "feat/other", EARLIER);
        deliver(fixture, 7, "pull_request", status().isAccepted());

        // An old event is a no-op, not an error: still 202, nothing moved.
        assertThat(snapshotColumns(pullRequest)).isEqualTo(current);
    }

    @Test
    void anEqualTimestampStillWritesBecauseTheReadIsAuthoritative() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);
        deliver(fixture, 7, "pull_request", status().isAccepted());
        long pullRequest = pullRequestId(fixture, 7);

        // The provider's updated_at has one-second resolution and two pushes can
        // share it, so "not older" has to mean >=, not >.
        provider.pullRequest(BASE_SHA, OTHER_HEAD_SHA, "feat/other", UPDATED_AT);
        deliver(fixture, 7, "pull_request", status().isAccepted());

        assertThat(jdbc.queryForObject("select head_sha from pull_request where id = ?", String.class,
                pullRequest)).isEqualTo(OTHER_HEAD_SHA);
    }

    @Test
    void anInvalidSignatureWritesNothingAndCallsNothing() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);
        String body = delivery(fixture, 7);
        String signature = sign(body, fixture.secret);
        String tampered = signature.substring(0, signature.length() - 1)
                + (signature.endsWith("a") ? "b" : "a");

        deliver(body, tampered, "pull_request", status().isUnauthorized());

        assertThat(jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository)).isZero();
        assertThat(provider.requests()).as("no outbound call may happen before verification").isZero();
    }

    @Test
    void anUnknownRepositoryAnswersExactlyLikeABadSignature() throws Exception {
        Fixture fixture = new Fixture();
        String unknown = """
                {"action":"synchronize","number":7,
                 "repository":{"id":"never-registered-%d","html_url":"%s/octo/repo"}}"""
                .formatted(SEQUENCE.incrementAndGet(), provider.instanceUrl());

        MvcResult unknownRepository = deliver(unknown, sign(unknown, fixture.secret), "pull_request",
                status().isUnauthorized());
        String body = delivery(fixture, 7);
        MvcResult badSignature = deliver(body, sign(body, "not-the-secret"), "pull_request",
                status().isUnauthorized());

        // Telling these apart would let anyone enumerate which repositories this
        // deployment has connected.
        assertThat(withoutTraceId(unknownRepository)).isEqualTo(withoutTraceId(badSignature));
        assertThat(provider.requests()).isZero();
    }

    @Test
    void aVerifiedDeliveryOfSomeOtherEventIsAcknowledgedAndIgnored() throws Exception {
        Fixture fixture = new Fixture();

        deliver(fixture, 7, "ping", status().isAccepted());

        assertThat(jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository)).isZero();
        assertThat(provider.requests()).isZero();
    }

    @Test
    void aReferenceToAnotherProjectsRequirementLeavesThePullRequestUnlinked() throws Exception {
        Fixture fixture = new Fixture();
        Fixture elsewhere = new Fixture();

        // REQ-<n> is the global requirement id, so this one exists — just not here.
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/REQ-" + elsewhere.requirement + "-borrowed",
                UPDATED_AT);
        deliver(fixture, 8, "pull_request", status().isAccepted());

        long foreign = pullRequestId(fixture, 8);
        assertThat(jdbc.queryForObject("select requirement_id from pull_request where id = ?", Long.class,
                foreign)).as("an id from another project resolves to no requirement at all").isNull();
        assertThat(jdbc.queryForObject("select count(*) from pull_request_requirement_event "
                + "where pull_request_id = ?", Integer.class, foreign)).isZero();

        // And it did not block ingestion: everything else about the pull request landed.
        assertThat(jdbc.queryForObject("select head_sha from pull_request where id = ?", String.class,
                foreign)).isEqualTo(HEAD_SHA);

        // The same shape with this project's own requirement does link.
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/REQ-" + fixture.requirement + "-mine", UPDATED_AT);
        deliver(fixture, 9, "pull_request", status().isAccepted());
        assertThat(jdbc.queryForObject("select requirement_id from pull_request where id = ?", Long.class,
                pullRequestId(fixture, 9))).isEqualTo(fixture.requirement);
    }

    @Test
    void aReferenceNobodyCanResolveIsSilentlyNoAssociation() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "chore/no-token-here", UPDATED_AT);
        deliver(fixture, 11, "pull_request", status().isAccepted());
        assertThat(jdbc.queryForObject("select requirement_id from pull_request where id = ?", Long.class,
                pullRequestId(fixture, 11))).isNull();

        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/REQ-999999999-ghost", UPDATED_AT);
        deliver(fixture, 12, "pull_request", status().isAccepted());
        assertThat(jdbc.queryForObject("select requirement_id from pull_request where id = ?", Long.class,
                pullRequestId(fixture, 12))).isNull();
    }

    @Test
    void theTitleIsUsedOnlyWhenTheBranchCarriesNoReference() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequestWithTitle(BASE_SHA, HEAD_SHA, "chore/cleanup",
                "Implements REQ-" + fixture.requirement + " at last", UPDATED_AT);

        deliver(fixture, 13, "pull_request", status().isAccepted());

        assertThat(jdbc.queryForObject("select requirement_id from pull_request where id = ?", Long.class,
                pullRequestId(fixture, 13))).isEqualTo(fixture.requirement);
    }

    @Test
    void theChangedEventIsPublishedInsideTheTransactionThatWroteTheRow() throws Exception {
        Fixture fixture = new Fixture();
        provider.pullRequest(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);

        deliver(fixture, 14, "pull_request", status().isAccepted());

        long pullRequest = pullRequestId(fixture, 14);
        assertThat(listener.received()).singleElement().satisfies(event -> {
            assertThat(event.pullRequestId()).isEqualTo(pullRequest);
            assertThat(event.headSha()).isEqualTo(HEAD_SHA);
            assertThat(event.reviewInputFingerprint()).isEqualTo(fingerprintOf(pullRequest));
        });
        assertThat(listener.transactionActive())
                .as("a listener must be able to join the transaction, so review can roll it back")
                .isTrue();
        assertThat(listener.rowsVisible())
                .as("the row the event talks about must already be readable in that transaction")
                .isEqualTo(1);
    }

    /**
     * The reason the event is synchronous and in-transaction: the listener
     * creates the PENDING Review, and there must be no committed state where the
     * pull request moved but that Review is missing.
     */
    @Test
    void aFailingListenerRollsTheWholeIngestionBack() {
        Fixture fixture = new Fixture();
        ScmRepository repository = repositories.findByProjectIdAndId(fixture.project, fixture.repository)
                .orElseThrow();
        listener.failNext();

        assertThatThrownBy(() -> sync.apply(repository, new PullRequestSnapshot(15, BASE_SHA, HEAD_SHA,
                "feat/other", "Title", null, UPDATED_AT, "424242", "octocat", List.of(MODIFIED))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String fingerprintOf(long pullRequest) {
        return jdbc.queryForObject("select review_input_fingerprint from pull_request where id = ?",
                String.class, pullRequest);
    }

    private java.util.Map<String, Object> snapshotColumns(long pullRequest) {
        return jdbc.queryForMap("select title, base_sha, head_sha, review_input_fingerprint, source_revision, "
                + "source_updated_at, changed_files::text, requirement_id from pull_request where id = ?",
                pullRequest);
    }

    private long pullRequestId(Fixture fixture, int number) {
        return jdbc.queryForObject("select id from pull_request where repository_id = ? "
                + "and external_number = ?", Long.class, fixture.repository, number);
    }

    private String withoutTraceId(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("traceId").asString()).isNotBlank();
        return body.path("code").asString() + "|" + body.path("message").asString();
    }

    private MvcResult deliver(Fixture fixture, int number, String event, ResultMatcher expected)
            throws Exception {
        String body = delivery(fixture, number);
        return deliver(body, sign(body, fixture.secret), event, expected);
    }

    private MvcResult deliver(String body, String signature, String event, ResultMatcher expected)
            throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(WEBHOOK).with(FROM_PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", event)
                        .header("X-GitHub-Delivery", "delivery-" + SEQUENCE.incrementAndGet()))
                .andExpect(expected)
                .andReturn();
    }

    /** What GitHub posts: the repository is identified by its numeric id and its site URL. */
    private String delivery(Fixture fixture, int number) {
        return """
                {"action":"synchronize","number":%d,
                 "repository":{"id":"%s","html_url":"%s/octo/repo"},
                 "pull_request":{"number":%d}}"""
                .formatted(number, fixture.externalId, provider.instanceUrl(), number);
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

    /** A project with a LEADER, a requirement and a repository pointed at the stub. */
    private final class Fixture {

        private final long owner;
        private final long project;
        private final long requirement;
        private final long repository;
        private final String externalId;
        private final String instanceIdentity;
        private final String secret;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            this.owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "scm-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "scm-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.requirement = jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                    Long.class, project);
            this.externalId = "repo-" + sequence;
            this.secret = "webhook-secret-" + sequence;
            this.instanceIdentity = InstanceIdentity.of(URI.create(provider.apiBase()));
            this.repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) "
                            + "values (?, 'GITHUB', ?, ?, ?, ?, ?) returning id",
                    Long.class, project, instanceIdentity, externalId, provider.apiBase(),
                    cipher.encrypt("token-" + externalId), cipher.encrypt(secret));
        }
    }

    // ----------------------------------------------------------------- provider

    /**
     * A real socket on loopback, so the whole client stack runs: the request line,
     * the pagination and {@link OutboundUrlPolicy} itself. It also scripts a
     * sequence of snapshots for one pull request, which no real repository could.
     */
    private static final class StubProvider {

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> requestLines = Collections.synchronizedList(new ArrayList<>());
        private volatile String authorization;
        private volatile String pullRequestJson = "";

        private StubProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** The user facing URL of the same instance, which is what a delivery carries. */
        private String instanceUrl() {
            return apiBase();
        }

        private void pullRequest(String baseSha, String headSha, String branch, Instant updatedAt) {
            pullRequestWithTitle(baseSha, headSha, branch, "A pull request", updatedAt);
        }

        private void pullRequestWithTitle(String baseSha, String headSha, String branch, String title,
                Instant updatedAt) {
            pullRequestJson = """
                    {"number":7,"title":"%s","updated_at":"%s",
                     "base":{"sha":"%s"},
                     "head":{"sha":"%s","ref":"%s"},
                     "user":{"id":424242,"login":"octocat"}}"""
                    .formatted(title, updatedAt, baseSha, headSha, branch);
        }

        private void reset() {
            requests.set(0);
            requestLines.clear();
            authorization = null;
            pullRequest(BASE_SHA, HEAD_SHA, "feat/other", UPDATED_AT);
        }

        private int requests() {
            return requests.get();
        }

        private List<String> requestLines() {
            return List.copyOf(requestLines);
        }

        private String authorization() {
            return authorization;
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            URI uri = exchange.getRequestURI();
            requestLines.add(uri.toString());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");

            String body;
            if (uri.getPath().endsWith("/files")) {
                // The second file carries no "patch" key at all, which is what a
                // binary file or one past the provider's diff limit looks like.
                body = uri.getQuery().endsWith("&page=1") ? """
                        [{"filename":"src/a.txt","status":"modified","patch":"@@ -1 +1 @@\\n-a\\n+b\\n"},
                         {"filename":"src/B.txt","status":"added"}]""" : "[]";
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

    // ----------------------------------------------------------------- listener

    /**
     * A test-scoped listener, so the publication itself is observable rather than
     * only its downstream effect. Being test-scoped, it cannot weaken the ArchUnit
     * rule, which imports production classes only.
     */
    @TestConfiguration
    static class ListenerConfiguration {

        @Bean
        RecordingListener recordingListener(JdbcTemplate jdbc) {
            return new RecordingListener(jdbc);
        }
    }

    static final class RecordingListener {

        private final JdbcTemplate jdbc;
        private final List<PullRequestChanged> received = new CopyOnWriteArrayList<>();
        private volatile boolean transactionActive;
        private volatile Integer rowsVisible;
        private volatile boolean fail;

        RecordingListener(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @EventListener
        void onPullRequestChanged(PullRequestChanged event) {
            received.add(event);
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            rowsVisible = jdbc.queryForObject("select count(*) from pull_request where id = ?",
                    Integer.class, event.pullRequestId());
            if (fail) {
                throw new IllegalStateException("this listener refuses the pull request");
            }
        }

        void reset() {
            received.clear();
            transactionActive = false;
            rowsVisible = null;
            fail = false;
        }

        void failNext() {
            fail = true;
        }

        List<PullRequestChanged> received() {
            return List.copyOf(received);
        }

        boolean transactionActive() {
            return transactionActive;
        }

        Integer rowsVisible() {
            return rowsVisible;
        }
    }
}
