package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * The batch 1 loop over real HTTP: register, log in, create a project, add a
 * member, write a requirement with acceptance criteria, freeze it, assign it,
 * and read the revision history back. Every other test in this batch works one
 * slice at a time; this is the only place where the wiring between them —
 * security filter chain, CSRF, error mapping, JSON shape — is actually proven.
 */
@SpringBootTest
class BatchOneApiTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aLeaderWalksTheWholeLoopAndTheDeveloperSeesItReadOnly() throws Exception {
        Client leader = register();
        Client developer = register();

        long project = leader.post("/api/projects", """
                {"name": "ForgePilot"}""").path("id").asLong();

        leader.post("/api/projects/" + project + "/members", """
                {"username": "%s", "role": "DEVELOPER"}""".formatted(developer.username));

        // The SCM identity is configured by the LEADER and is a key plus a display
        // name only: nothing in this batch authorises against it.
        leader.patch("/api/projects/" + project + "/members/" + developer.userId, """
                {"scmExternalUserId": "gh-2001", "scmUsername": "octodev"}""");

        long requirement = leader.post("/api/projects/" + project + "/requirements", """
                {"title": "Log in with a local account",
                 "background": "Reviewers need identity before anything else.",
                 "description": "Form login backed by user_account.",
                 "acceptanceCriteria": [{"text": "Wrong password and unknown user look identical."},
                                        {"text": "Logging out kills the session."}]}""")
                .path("id").asLong();

        String base = "/api/projects/" + project + "/requirements/" + requirement;

        JsonNode created = leader.read(base);
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("currentRevision").path("seq").asInt()).isEqualTo(1);
        // Batch 3 moved review activity out of this response entirely. It is derived
        // from pull_request and review, and the dependency arrow runs review ->
        // requirement, so requirement cannot compute it without cycling the feature
        // graph. Asserting its absence here is the boundary check: if it ever comes
        // back, something reached across in the wrong direction.
        assertThat(created.has("reviewActivity")).isFalse();
        JsonNode firstCriteria = created.path("currentRevision").path("acceptanceCriteria");
        assertThat(firstCriteria.size()).isEqualTo(2);
        String firstKey = firstCriteria.get(0).path("acKey").asString();
        String secondKey = firstCriteria.get(1).path("acKey").asString();
        assertThat(firstKey).isNotBlank().isNotEqualTo(secondKey);

        // A DEVELOPER reads everything in the project and changes nothing.
        assertThat(developer.read(base).path("id").asLong()).isEqualTo(requirement);
        developer.postExpecting(base + "/status", """
                {"status": "READY"}""", status().isForbidden());

        leader.post(base + "/status", """
                {"status": "READY"}""");

        // Frozen: the DRAFT edit path closes once the requirement is READY. Judging
        // that needs the current status, so it is a 409 (api-contract 0).
        leader.patchExpecting(base, """
                {"title": "Sneak an edit in", "acceptanceCriteria": [{"acKey": "%s", "text": "changed"}]}"""
                .formatted(firstKey), status().isConflict());

        // First assignment moves READY -> IN_DEVELOPMENT in the same transaction.
        JsonNode assigned = leader.post(base + "/assignee", """
                {"userId": %d}""".formatted(developer.userId));
        assertThat(assigned.path("status").asString()).isEqualTo("IN_DEVELOPMENT");
        assertThat(assigned.path("assigneeId").asLong()).isEqualTo(developer.userId);
        assertThat(assigned.path("assigneeUsername").asString()).isEqualTo(developer.username);

        // Reassignment moves the status neither back nor forward.
        assertThat(leader.post(base + "/assignee", """
                {"userId": %d}""".formatted(leader.userId)).path("status").asString())
                .isEqualTo("IN_DEVELOPMENT");

        // Publishing a new revision keeps each existing acKey and reorders freely.
        JsonNode published = leader.post(base + "/revisions", """
                {"title": "Log in with a local account",
                 "changeReason": "Reviewers asked for an explicit lockout rule.",
                 "acceptanceCriteria": [{"acKey": "%s", "text": "Logging out kills the session."},
                                        {"acKey": "%s", "text": "Wrong password and unknown user look identical."},
                                        {"text": "Changing the password kills every other session."}]}"""
                .formatted(secondKey, firstKey));
        assertThat(published.path("currentRevision").path("seq").asInt()).isEqualTo(2);

        JsonNode history = leader.read(base + "/revisions");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(history.get(0).path("acceptanceCriteria").size()).isEqualTo(2);
        assertThat(history.get(1).path("changeReason").asString())
                .isEqualTo("Reviewers asked for an explicit lockout rule.");

        // The same criterion kept its key across the revision, despite moving position.
        JsonNode republished = history.get(1).path("acceptanceCriteria");
        assertThat(republished.get(0).path("acKey").asString()).isEqualTo(secondKey);
        assertThat(republished.get(1).path("acKey").asString()).isEqualTo(firstKey);
        assertThat(republished.get(2).path("acKey").asString()).isNotIn(firstKey, secondKey);
    }

    @Test
    void anotherProjectsIdsAreInvisibleOverHttp() throws Exception {
        Client owner = register();
        Client outsider = register();

        long foreign = owner.post("/api/projects", """
                {"name": "Private"}""").path("id").asLong();
        long foreignRequirement = owner.post("/api/projects/" + foreign + "/requirements", """
                {"title": "Secret", "acceptanceCriteria": [{"text": "hidden"}]}""")
                .path("id").asLong();
        long ownProject = outsider.post("/api/projects", """
                {"name": "Mine"}""").path("id").asLong();

        // Someone else's project answers exactly like an id that was never issued,
        // so existence cannot be probed.
        outsider.readExpecting("/api/projects/" + foreign, status().isNotFound());
        outsider.readExpecting("/api/projects/" + (foreign + 9_000), status().isNotFound());
        outsider.readExpecting("/api/projects/" + foreign + "/members", status().isNotFound());
        outsider.readExpecting("/api/projects/" + foreign + "/requirements/" + foreignRequirement,
                status().isNotFound());

        // Nor can a foreign requirement be dragged into a project the caller owns.
        outsider.readExpecting("/api/projects/" + ownProject + "/requirements/" + foreignRequirement,
                status().isNotFound());
        outsider.postExpecting("/api/projects/" + ownProject + "/requirements/" + foreignRequirement
                + "/status", """
                {"status": "CANCELED"}""", status().isNotFound());

        assertThat(outsider.read("/api/projects").size()).isEqualTo(1);
    }

    @Test
    void writesWithoutTheCsrfHeaderAreRejected() throws Exception {
        Client leader = register();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/projects")
                        .session(leader.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "No token"}"""))
                .andExpect(status().isForbidden());

        assertThat(leader.read("/api/projects").size()).isZero();
    }

    @Test
    void anonymousCallersGetTheSameErrorShapeAsEveryoneElse() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");
    }

    // -------------------------------------------------------------------- client

    private Client register() throws Exception {
        Client client = new Client("pilot-" + SEQUENCE.incrementAndGet());
        client.bootstrapCsrf();
        client.post("/api/auth/register", """
                {"username": "%s", "password": "%s"}""".formatted(client.username, Client.PASSWORD));
        client.login();
        return client;
    }

    /** One browser: its session, plus the CSRF cookie it echoes back as a header. */
    private final class Client {

        private static final String PASSWORD = "correct-horse-battery";

        private final MockHttpSession session = new MockHttpSession();
        private final String username;
        private Cookie csrf;
        private long userId;

        private Client(String username) {
            this.username = username;
        }

        private void bootstrapCsrf() throws Exception {
            // Anonymous, but it must still hand out the token a JS client needs.
            csrf = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").session(session))
                    .andReturn().getResponse().getCookie("XSRF-TOKEN");
            assertThat(csrf).as("GET /api/auth/me must issue the XSRF-TOKEN cookie").isNotNull();
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
            // Spring Security re-issues the token on authentication; keep the new one.
            Cookie reissued = result.getResponse().getCookie("XSRF-TOKEN");
            if (reissued != null && reissued.getValue() != null && !reissued.getValue().isEmpty()) {
                csrf = reissued;
            }
            userId = body(result).path("id").asLong();
        }

        private JsonNode read(String path) throws Exception {
            return body(mockMvc.perform(MockMvcRequestBuilders.get(path).session(session))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private void readExpecting(String path, ResultMatcher expected) throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get(path).session(session)).andExpect(expected);
        }

        private JsonNode post(String path, String payload) throws Exception {
            return body(mockMvc.perform(write(MockMvcRequestBuilders.post(path), payload))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private void postExpecting(String path, String payload, ResultMatcher expected) throws Exception {
            mockMvc.perform(write(MockMvcRequestBuilders.post(path), payload)).andExpect(expected);
        }

        private JsonNode patch(String path, String payload) throws Exception {
            return body(mockMvc.perform(write(MockMvcRequestBuilders.patch(path), payload))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private void patchExpecting(String path, String payload, ResultMatcher expected) throws Exception {
            mockMvc.perform(write(MockMvcRequestBuilders.patch(path), payload)).andExpect(expected);
        }

        private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String payload) {
            return request.session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("no response body to read").isNotEmpty();
        return json.readTree(content);
    }
}
