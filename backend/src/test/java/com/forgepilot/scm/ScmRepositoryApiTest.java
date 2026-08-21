package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Connecting a repository, over real HTTP with a real session: who may do it, what
 * comes back, what may still change afterwards and what may not.
 */
@SpringBootTest
class ScmRepositoryApiTest extends ScmTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Allowlisted for tests, so what refuses a change here is the rule under test, not the SSRF policy. */
    private static final String INSTANCE = "http://127.0.0.1:34567";
    private static final String OTHER_INSTANCE = "http://127.0.0.1:34568";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aLeaderConnectsARepositoryAndNeverSeesTheCredentialsAgain() throws Exception {
        Client leader = register();
        long project = leader.post("/api/projects", """
                {"name": "Connected"}""").path("id").asLong();

        MvcResult created = leader.postExpecting("/api/projects/" + project + "/scm/repositories", """
                {"provider": "GITHUB", "externalId": "123456", "apiBase": "%s/api/v3",
                 "token": "ghp-secret-token", "webhookSecret": "hook-secret"}"""
                .formatted(INSTANCE), status().isCreated());

        JsonNode body = body(created);
        assertThat(body.path("instanceIdentity").asString()).isEqualTo("127.0.0.1:34567");
        assertThat(body.path("externalId").asString()).isEqualTo("123456");
        assertThat(body.path("apiBase").asString()).isEqualTo(INSTANCE + "/api/v3");
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("ghp-secret-token")
                .doesNotContain("hook-secret")
                .doesNotContain("encrypted");

        // Stored encrypted, not as given: the column never holds the token itself.
        assertThat(jdbc.queryForMap("select encrypted_token, encrypted_secret from scm_repository "
                + "where id = ?", body.path("id").asLong()).values())
                .noneSatisfy(stored -> assertThat(stored).isIn("ghp-secret-token", "hook-secret"));
    }

    @Test
    void aProjectGetsOneRepositoryAndTheIdentityIsGlobal() throws Exception {
        Client leader = register();
        long project = leader.post("/api/projects", """
                {"name": "One"}""").path("id").asLong();
        String externalId = "shared-" + SEQUENCE.incrementAndGet();
        connect(leader, project, externalId, INSTANCE);

        // A second repository in the same project, and the same repository in
        // another project, are both refused by unique constraints rather than by a
        // service pre-check.
        leader.postExpecting("/api/projects/" + project + "/scm/repositories", registerBody("other", INSTANCE),
                status().isConflict());

        long second = leader.post("/api/projects", """
                {"name": "Two"}""").path("id").asLong();
        leader.postExpecting("/api/projects/" + second + "/scm/repositories",
                registerBody(externalId, INSTANCE), status().isConflict());
    }

    @Test
    void anApiBaseInsideTheInfrastructureIsRefused() throws Exception {
        Client leader = register();
        long project = leader.post("/api/projects", """
                {"name": "SSRF"}""").path("id").asLong();

        // The policy runs on the configuration path too, not only on the outbound
        // call, and 127.0.0.1 being allowlisted for tests does not open this up.
        leader.postExpecting("/api/projects/" + project + "/scm/repositories",
                registerBody("1", "http://169.254.169.254/latest/meta-data"), status().isUnprocessableEntity());
        leader.postExpecting("/api/projects/" + project + "/scm/repositories",
                registerBody("1", "http://10.0.0.5/api/v3"), status().isUnprocessableEntity());
        leader.postExpecting("/api/projects/" + project + "/scm/repositories",
                registerBody("1", "file:///etc/passwd"), status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject("select count(*) from scm_repository where project_id = ?",
                Integer.class, project)).isZero();
    }

    @Test
    void beforeAnyPullRequestTheIdentityIsStillFree() throws Exception {
        Client leader = register();
        long project = leader.post("/api/projects", """
                {"name": "Free"}""").path("id").asLong();
        long repository = connect(leader, project, "before-" + SEQUENCE.incrementAndGet(), INSTANCE);

        JsonNode moved = leader.patch("/api/projects/" + project + "/scm/repositories/" + repository, """
                {"externalId": "renumbered", "apiBase": "%s"}""".formatted(OTHER_INSTANCE));

        assertThat(moved.path("externalId").asString()).isEqualTo("renumbered");
        assertThat(moved.path("instanceIdentity").asString()).isEqualTo("127.0.0.1:34568");
    }

    @Test
    void oncePullRequestsExistTheIdentityIsFrozenButTheApiBaseStillMoves() throws Exception {
        Client leader = register();
        long project = leader.post("/api/projects", """
                {"name": "Frozen"}""").path("id").asLong();
        String externalId = "frozen-" + SEQUENCE.incrementAndGet();
        long repository = connect(leader, project, externalId, INSTANCE);
        insertPullRequest(project, repository, 1);

        String path = "/api/projects/" + project + "/scm/repositories/" + repository;

        // The repository or the instance would silently reattribute every
        // fingerprint, association and audit row already recorded.
        leader.patchExpecting(path, """
                {"externalId": "somewhere-else"}""", status().isConflict());
        leader.patchExpecting(path, """
                {"apiBase": "%s"}""".formatted(OTHER_INSTANCE), status().isConflict());

        // The same instance reached by another URL is not a change of identity.
        JsonNode moved = leader.patch(path, """
                {"apiBase": "%s/api/v3/"}""".formatted(INSTANCE));
        assertThat(moved.path("apiBase").asString()).isEqualTo(INSTANCE + "/api/v3/");
        assertThat(moved.path("instanceIdentity").asString()).isEqualTo("127.0.0.1:34567");
        assertThat(moved.path("externalId").asString()).isEqualTo(externalId);

        // Credentials were never frozen.
        leader.patch(path, """
                {"token": "rotated-token", "webhookSecret": "rotated-secret"}""");
    }

    @Test
    void onlyALeaderMayConnectOrChangeARepository() throws Exception {
        Client leader = register();
        Client developer = register();
        long project = leader.post("/api/projects", """
                {"name": "Roles"}""").path("id").asLong();
        leader.post("/api/projects/" + project + "/members", """
                {"username": "%s", "role": "DEVELOPER"}""".formatted(developer.username));
        long repository = connect(leader, project, "roles-" + SEQUENCE.incrementAndGet(), INSTANCE);

        // A member without the role knows the project exists, so 403 tells them nothing new.
        developer.postExpecting("/api/projects/" + project + "/scm/repositories",
                registerBody("x", INSTANCE), status().isForbidden());
        developer.patchExpecting("/api/projects/" + project + "/scm/repositories/" + repository, """
                {"token": "not-yours"}""", status().isForbidden());

        // A stranger gets the same answer as for a project that does not exist.
        Client stranger = register();
        stranger.postExpecting("/api/projects/" + project + "/scm/repositories",
                registerBody("x", INSTANCE), status().isNotFound());
    }

    @Test
    void anotherProjectsRepositoryAndPullRequestIdsAreInvisible() throws Exception {
        Client owner = register();
        Client outsider = register();
        long foreign = owner.post("/api/projects", """
                {"name": "Theirs"}""").path("id").asLong();
        long foreignRepository = connect(owner, foreign, "hidden-" + SEQUENCE.incrementAndGet(), INSTANCE);
        long foreignPullRequest = insertPullRequest(foreign, foreignRepository, 1);

        long own = outsider.post("/api/projects", """
                {"name": "Mine"}""").path("id").asLong();

        // Another project's id and an id that was never issued answer identically,
        // so existence cannot be probed by trying ids.
        String never = String.valueOf(foreignRepository + 9_000);
        assertThat(outsider.patchExpecting("/api/projects/" + own + "/scm/repositories/" + foreignRepository,
                "{}", status().isNotFound()).getResponse().getContentAsString())
                .isEqualTo(sameShapeAs(outsider.patchExpecting(
                        "/api/projects/" + own + "/scm/repositories/" + never, "{}", status().isNotFound())));

        assertThat(outsider.readExpecting("/api/projects/" + own + "/pull-requests/" + foreignPullRequest,
                status().isNotFound()).getResponse().getContentAsString())
                .isEqualTo(sameShapeAs(outsider.readExpecting(
                        "/api/projects/" + own + "/pull-requests/" + (foreignPullRequest + 9_000),
                        status().isNotFound())));

        // And the project itself is just as invisible.
        outsider.readExpecting("/api/projects/" + foreign + "/pull-requests/" + foreignPullRequest,
                status().isNotFound());
        outsider.patchExpecting("/api/projects/" + foreign + "/scm/repositories/" + foreignRepository, "{}",
                status().isNotFound());
    }

    @Test
    void aMemberReadsTheStoredPullRequest() throws Exception {
        Client leader = register();
        long project = leader.post("/api/projects", """
                {"name": "Readable"}""").path("id").asLong();
        long repository = connect(leader, project, "readable-" + SEQUENCE.incrementAndGet(), INSTANCE);
        long pullRequest = insertPullRequest(project, repository, 42);

        JsonNode body = leader.read("/api/projects/" + project + "/pull-requests/" + pullRequest);

        assertThat(body.path("externalNumber").asInt()).isEqualTo(42);
        assertThat(body.path("headSha").asString()).isEqualTo("head-sha");
        assertThat(body.path("reviewInputFingerprint").asString()).isEqualTo("fingerprint");
    }

    // ------------------------------------------------------------------ helpers

    /** Everything the caller can compare, which is the body minus the per-response traceId. */
    private String withoutTraceId(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("traceId").asString()).isNotBlank();
        assertThat(body.size()).as("the error body has exactly code, message and traceId").isEqualTo(3);
        return body.path("code").asString() + "|" + body.path("message").asString();
    }

    private long connect(Client leader, long project, String externalId, String apiBase) throws Exception {
        return body(leader.postExpecting("/api/projects/" + project + "/scm/repositories",
                registerBody(externalId, apiBase), status().isCreated())).path("id").asLong();
    }

    private static String registerBody(String externalId, String apiBase) {
        return """
                {"provider": "GITHUB", "externalId": "%s", "apiBase": "%s",
                 "token": "token", "webhookSecret": "secret"}""".formatted(externalId, apiBase);
    }

    private long insertPullRequest(long project, long repository, int number) {
        return jdbc.queryForObject("insert into pull_request (project_id, repository_id, external_number, "
                + "base_sha, head_sha, review_input_fingerprint, changed_files, author_external_user_id, "
                + "author_username) values (?, ?, ?, 'base-sha', 'head-sha', 'fingerprint', '[]'::jsonb, "
                + "'gh-1', 'octocat') returning id", Long.class, project, repository, number);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Client register() throws Exception {
        Client client = new Client("scm-api-" + SEQUENCE.incrementAndGet());
        client.bootstrapCsrf();
        client.post("/api/auth/register", """
                {"username": "%s", "password": "%s"}""".formatted(client.username, Client.PASSWORD));
        client.login();
        return client;
    }

    /** One browser: its session plus the CSRF cookie it echoes back as a header. */
    private final class Client {

        private static final String PASSWORD = "correct-horse-battery";

        private final MockHttpSession session = new MockHttpSession();
        private final String username;
        private Cookie csrf;

        private Client(String username) {
            this.username = username;
        }

        private void bootstrapCsrf() throws Exception {
            csrf = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").session(session))
                    .andReturn().getResponse().getCookie("XSRF-TOKEN");
            assertThat(csrf).isNotNull();
        }

        private void login() throws Exception {
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                            .session(session)
                            .param("username", username)
                            .param("password", PASSWORD)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie reissued = result.getResponse().getCookie("XSRF-TOKEN");
            if (reissued != null && reissued.getValue() != null && !reissued.getValue().isEmpty()) {
                csrf = reissued;
            }
        }

        private JsonNode read(String path) throws Exception {
            return body(mockMvc.perform(MockMvcRequestBuilders.get(path).session(session))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private MvcResult readExpecting(String path, ResultMatcher expected) throws Exception {
            return mockMvc.perform(MockMvcRequestBuilders.get(path).session(session))
                    .andExpect(expected).andReturn();
        }

        private JsonNode post(String path, String payload) throws Exception {
            return body(postExpecting(path, payload, status().is2xxSuccessful()));
        }

        private MvcResult postExpecting(String path, String payload, ResultMatcher expected)
                throws Exception {
            return mockMvc.perform(write(MockMvcRequestBuilders.post(path), payload))
                    .andExpect(expected).andReturn();
        }

        private JsonNode patch(String path, String payload) throws Exception {
            return body(patchExpecting(path, payload, status().is2xxSuccessful()));
        }

        private MvcResult patchExpecting(String path, String payload, ResultMatcher expected)
                throws Exception {
            return mockMvc.perform(write(MockMvcRequestBuilders.patch(path), payload))
                    .andExpect(expected).andReturn();
        }

        private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String payload) {
            return request.session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue());
        }
    }
}
