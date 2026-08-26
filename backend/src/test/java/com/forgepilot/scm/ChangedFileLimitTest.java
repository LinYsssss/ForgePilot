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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.forgepilot.common.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ChangedFile#MAX_TOTAL_CHARS} and the two guards that read it. An
 * over-limit pull request "does not exist in the system at all", and the whole
 * path is easy to leave untested: the constant is referenced nowhere else in
 * test code, so without this class neither guard would ever execute.
 *
 * <p>The two guards are not the same guard. {@code GitHubClient.changedFiles}
 * counts raw characters while it pages, so an outsized pull request fails before
 * it is accumulated in memory; {@code PullRequestSyncService.manifest} measures the
 * serialized JSONB, which is what the column actually has to hold and which is
 * larger than the raw characters whenever a patch needs escaping. They raise the
 * same message, so this class tells them apart by what the client did or did not
 * fetch.
 *
 * <p>Both directions of the threshold are pinned, because "refuses something big"
 * is a much weaker claim than "refuses exactly above the limit": the accepted case
 * is a manifest of exactly {@code MAX_TOTAL_CHARS} characters and the refused one
 * is the same manifest with one character more.
 */
@SpringBootTest
class ChangedFileLimitTest extends ScmTestBase {

    private static final String WEBHOOK = "/api/scm/github/webhook";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String BASE_SHA = "1111111111111111111111111111111111111111";
    private static final String HEAD_SHA = "2222222222222222222222222222222222222222";
    private static final Instant UPDATED_AT = Instant.parse("2026-08-21T12:00:00Z");
    private static final String PATH = "src/big.txt";

    private static StubProvider provider;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PullRequestSyncService sync;

    @Autowired
    private ScmRepositoryRepository repositories;

    @Autowired
    private ScmSecretCipher cipher;

    @Autowired
    private JdbcTemplate jdbc;

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
    }

    /**
     * One character over, and "completely absent" has to be literal: not a
     * row with a truncated manifest, not a row marked as failed — no row.
     */
    @Test
    void aManifestOneCharacterOverTheLimitIsRefusedAndTheRowNeverExists() {
        Fixture fixture = new Fixture();
        ScmRepository repository = repositories.findByProjectIdAndId(fixture.project, fixture.repository)
                .orElseThrow();

        assertThatThrownBy(() -> sync.apply(repository,
                snapshot(31, manifestOfExactly(ChangedFile.MAX_TOTAL_CHARS + 1))))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(refused.getMessage())
                            .isEqualTo("This pull request's diff is larger than this deployment stores.");
                });

        assertThat(pullRequestCount(fixture)).isZero();
    }

    /**
     * The control, and the reason the case above is a statement about the threshold
     * rather than about size in general: the same manifest one character shorter is
     * stored whole, patch and all.
     */
    @Test
    void aManifestOfExactlyTheLimitIsStoredWhole() {
        Fixture fixture = new Fixture();
        ScmRepository repository = repositories.findByProjectIdAndId(fixture.project, fixture.repository)
                .orElseThrow();
        List<ChangedFile> files = manifestOfExactly(ChangedFile.MAX_TOTAL_CHARS);

        sync.apply(repository, snapshot(32, files));

        assertThat(pullRequestCount(fixture)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select length(changed_files->0->>'patch') from pull_request "
                + "where repository_id = ?", Integer.class, fixture.repository))
                .isEqualTo(files.get(0).patch().length());
    }

    /**
     * The client's own guard, on the way in. Page one already crosses the limit, so
     * a client that only checked after collecting everything would go on to ask for
     * page two — which is why the request lines are asserted and not just the
     * status.
     */
    @Test
    void anOversizedDiffIsRefusedWhileItIsBeingFetchedRatherThanAfterwards() throws Exception {
        Fixture fixture = new Fixture();
        provider.serveFilePages(pageOfExactly(ChangedFile.MAX_TOTAL_CHARS + 1),
                page(List.of(new ChangedFile("src/second-page.txt", "added", "@@"))));

        deliver(fixture, 33);

        assertThat(provider.requestLines()).containsExactly(
                "/repositories/" + fixture.externalId + "/pulls/33",
                "/repositories/" + fixture.externalId + "/pulls/33/files?per_page=100&page=1");
        assertThat(pullRequestCount(fixture)).isZero();
    }

    /**
     * Escaping is why the two guards cannot be one. Every character of this patch
     * is a quote, so the raw count stays under the limit and the client lets it
     * through, while the JSONB the column would have to hold is twice the size.
     * Without the second guard this delivery would be stored.
     */
    @Test
    void aDiffThatOnlyGrowsPastTheLimitOnceEscapedIsStillRefused() throws Exception {
        Fixture fixture = new Fixture();
        String patch = "\"".repeat(ChangedFile.MAX_TOTAL_CHARS - PATH.length());
        provider.serveFilePages(page(List.of(new ChangedFile(PATH, "modified", patch))));

        assertThat(patch.length() + PATH.length()).isLessThanOrEqualTo(ChangedFile.MAX_TOTAL_CHARS);
        assertThat(json.writeValueAsString(List.of(new ChangedFile(PATH, "modified", patch))).length())
                .isGreaterThan(ChangedFile.MAX_TOTAL_CHARS);

        deliver(fixture, 34);

        // The client did fetch it all, including the empty second page: this one is
        // refused by the manifest guard, not by the counting one.
        assertThat(provider.requestLines()).hasSize(2);
        assertThat(pullRequestCount(fixture)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A single-file manifest whose serialized form is exactly {@code target}
     * characters. The padding is a character JSON never escapes, so one added
     * character is one added character of manifest, and the length is asserted here
     * rather than assumed — an off-by-one in this helper would silently move the
     * threshold the tests claim to pin.
     */
    private List<ChangedFile> manifestOfExactly(int target) {
        int overhead = json.writeValueAsString(List.of(new ChangedFile(PATH, "modified", ""))).length();
        List<ChangedFile> files = List.of(new ChangedFile(PATH, "modified", "a".repeat(target - overhead)));
        assertThat(json.writeValueAsString(ChangedFile.canonicalOrder(files)).length())
                .as("the manifest handed to the service must be exactly %d characters", target)
                .isEqualTo(target);
        return files;
    }

    /**
     * A full page of 100 files whose paths and patches add up to exactly
     * {@code target} raw characters, which is what the client counts.
     */
    private String pageOfExactly(int target) {
        List<ChangedFile> files = new ArrayList<>();
        int remaining = target;
        for (int index = 0; index < 100; index++) {
            String path = "src/%03d.txt".formatted(index);
            int patch = index == 99 ? remaining - path.length() : (target / 100) - path.length();
            files.add(new ChangedFile(path, "modified", "a".repeat(patch)));
            remaining -= path.length() + patch;
        }
        assertThat(files.stream().mapToInt(file -> file.path().length() + file.patch().length()).sum())
                .isEqualTo(target);
        return page(files);
    }

    private String page(List<ChangedFile> files) {
        return files.stream()
                .map(file -> json.writeValueAsString(new GitHubFile(file.path(), file.changeType(),
                        file.patch())))
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** The provider's own field names, which is what the client reads. */
    private record GitHubFile(String filename, String status, String patch) {
    }

    private PullRequestSnapshot snapshot(int number, List<ChangedFile> files) {
        return new PullRequestSnapshot(number, BASE_SHA, HEAD_SHA, "feat/x", "Big", null, UPDATED_AT,
                "424242", "octocat", files);
    }

    private int pullRequestCount(Fixture fixture) {
        Integer value = jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, fixture.repository);
        return value == null ? 0 : value;
    }

    private void deliver(Fixture fixture, int number) throws Exception {
        String body = """
                {"action":"synchronize","number":%d,
                 "repository":{"id":"%s","html_url":"%s/octo/repo"},
                 "pull_request":{"number":%d}}"""
                .formatted(number, fixture.externalId, provider.apiBase(), number);
        mockMvc.perform(MockMvcRequestBuilders.post(WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Hub-Signature-256", sign(body, fixture.secret))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "delivery-" + SEQUENCE.incrementAndGet()))
                .andExpect(status().isUnprocessableEntity());
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
                    Long.class, "limit-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "limit-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.externalId = "limit-repo-" + sequence;
            this.secret = "limit-secret-" + sequence;
            this.repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) values (?, 'GITHUB', ?, ?, ?, ?, ?) "
                            + "returning id",
                    Long.class, project, InstanceIdentity.of(URI.create(provider.apiBase())), externalId,
                    provider.apiBase(), cipher.encrypt("token"), cipher.encrypt(secret));
        }
    }

    // ----------------------------------------------------------------- provider

    /** A real socket, so the paging loop and its counter run for real. */
    private static final class StubProvider {

        private final HttpServer server;
        private final List<String> requestLines = Collections.synchronizedList(new ArrayList<>());
        private volatile List<String> filePages = List.of("[]");

        private StubProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void reset() {
            requestLines.clear();
            filePages = List.of("[]");
        }

        private void serveFilePages(String... pages) {
            filePages = List.of(pages);
        }

        private List<String> requestLines() {
            return List.copyOf(requestLines);
        }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            requestLines.add(uri.toString());
            String body;
            if (uri.getPath().endsWith("/files")) {
                int page = Integer.parseInt(uri.getQuery().replaceAll(".*page=", ""));
                body = page <= filePages.size() ? filePages.get(page - 1) : "[]";
            } else {
                body = """
                        {"number":7,"title":"Big","updated_at":"%s","base":{"sha":"%s"},
                         "head":{"sha":"%s","ref":"feat/x"},"user":{"id":424242,"login":"octocat"}}"""
                        .formatted(UPDATED_AT, BASE_SHA, HEAD_SHA);
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
