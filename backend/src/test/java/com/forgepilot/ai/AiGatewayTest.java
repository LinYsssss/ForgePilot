package com.forgepilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The gateway against a real socket, with no credential in existence.
 *
 * <p>The provider is {@code com.sun.net.httpserver.HttpServer} from the JDK
 * (D015.8): it is a real HTTP/1.1 server, so a timeout can actually fire, a
 * malformed body can actually arrive, and — the point of this class — every
 * attempt can be <em>counted</em>. "Exactly one retry" is proved by the number
 * of requests the provider received, never by reading the loop that sends them.
 *
 * <p>It binds port 0 and is injected through {@code forgepilot.ai.base-url},
 * which is only possible because no provider host is hardcoded (D015.8): here
 * the production requirement and the test seam are the same thing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiGatewayTest extends PostgresTestBase {

    /** Generous enough that a loopback round trip never trips it by accident. */
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final Duration LONGER_THAN_THE_TIMEOUT = Duration.ofSeconds(3);
    private static final String API_KEY = "test-only-not-a-real-key";
    private static final String CHAT_MODEL = "stub-chat-model";
    private static final String EMBEDDING_MODEL = "stub-embedding-model";

    private static final HttpServer PROVIDER;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final List<String> AUTHORIZATION = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> BODIES = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicReference<HttpHandler> BEHAVIOUR = new AtomicReference<>();

    static {
        try {
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException cannotBind) {
            throw new IllegalStateException("the stub provider could not bind", cannotBind);
        }
        // A pool rather than the default single dispatcher: the timeout test
        // leaves one exchange sleeping, and the retry must not queue behind it.
        PROVIDER.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread worker = new Thread(runnable, "stub-provider");
            worker.setDaemon(true);
            return worker;
        }));
        PROVIDER.createContext("/v1", exchange -> {
            REQUESTS.incrementAndGet();
            AUTHORIZATION.add(exchange.getRequestHeaders().getFirst("Authorization"));
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            BEHAVIOUR.get().handle(exchange);
        });
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("forgepilot.ai.base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1");
        registry.add("forgepilot.ai.api-key", () -> API_KEY);
        registry.add("forgepilot.ai.chat-model", () -> CHAT_MODEL);
        registry.add("forgepilot.ai.timeout", () -> TIMEOUT.toMillis() + "ms");
    }

    @Autowired
    private AiGateway gateway;

    @Autowired
    private AiCallLogRepository callLogs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void forgetPreviousRequests() {
        REQUESTS.set(0);
        AUTHORIZATION.clear();
        BODIES.clear();
    }

    // ------------------------------------------------------------- the one retry

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void aTransientFailureIsSentExactlyTwiceAndBothAttemptsAreRecorded(int status) {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, status, "{\"error\":\"try later\"}"));

        assertThatThrownBy(() -> gateway.chat("prompt", null, AiUseCase.REQUIREMENT_QUALITY, fixture.revisionContext()))
                .isInstanceOf(ApiException.class)
                .hasMessage("The AI provider call did not succeed.");

        // Two, not three: ARCHITECTURE.md 7.2 buys one retry and no more.
        assertThat(REQUESTS).hasValue(2);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .hasSize(2)
                .allSatisfy(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(AiCallStatus.FAILED);
                    assertThat(attempt.getError()).isEqualTo("HTTP " + status);
                    assertThat(attempt.getUseCase()).isEqualTo(AiUseCase.REQUIREMENT_QUALITY);
                    assertThat(attempt.getModel()).isEqualTo(CHAT_MODEL);
                    assertThat(attempt.getRequirementId()).isEqualTo(fixture.requirement);
                    assertThat(attempt.getRequirementRevisionId()).isEqualTo(fixture.revision);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 422})
    void aPermanentFailureIsNeverSentASecondTime(int status) {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, status, "{\"error\":\"your request is wrong\"}"));

        assertThatThrownBy(() -> gateway.chat("prompt", null, AiUseCase.REQUIREMENT_QUALITY, fixture.revisionContext()))
                .isInstanceOf(ApiException.class);

        // The counter stopping at 1 is the whole assertion: an identical second
        // request could only earn the identical refusal.
        assertThat(REQUESTS).hasValue(1);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getError()).isEqualTo("HTTP " + status));
    }

    @Test
    void theTimeoutFiresAndIsRecordedApartFromOtherFailures() {
        Fixture fixture = new Fixture();
        // A perfectly good answer, only far too late. If the timeout did not fire
        // this call would succeed and this test would fail on the exception.
        BEHAVIOUR.set(exchange -> {
            sleep(LONGER_THAN_THE_TIMEOUT);
            respond(exchange, 200, chatAnswer("late"));
        });

        assertThatThrownBy(() -> gateway.chat("prompt", null, AiUseCase.IMPLEMENTATION_GUIDANCE,
                fixture.revisionContext())).isInstanceOf(ApiException.class);

        // 7.2 treats a timeout as transient, so it is retried once like any other
        // transient failure — and recorded as its own status, not as FAILED.
        assertThat(REQUESTS).hasValue(2);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .hasSize(2)
                .allSatisfy(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(AiCallStatus.TIMEOUT);
                    assertThat(attempt.getLatencyMs())
                            .as("gave up at the configured timeout instead of waiting for the answer")
                            .isLessThan((int) LONGER_THAN_THE_TIMEOUT.toMillis());
                });
    }

    // -------------------------------------------------------------- the wiring

    @Test
    void theAuthorizationHeaderCarriesTheConfiguredValue() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 200, chatAnswer("an answer")));

        String answer = gateway.chat("prompt", "{\"type\":\"object\"}", AiUseCase.REQUIREMENT_QUALITY,
                fixture.revisionContext());

        assertThat(answer).isEqualTo("an answer");
        assertThat(AUTHORIZATION).containsExactly("Bearer " + API_KEY);
        assertThat(BODIES).singleElement().satisfies(body -> {
            assertThat(body).contains("\"model\":\"" + CHAT_MODEL + "\"");
            assertThat(body).contains("\"json_schema\"");
        });
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(AiCallStatus.SUCCESS);
                    assertThat(attempt.getError()).isNull();
                    assertThat(attempt.getPromptToken()).isEqualTo(11);
                    assertThat(attempt.getCompletionToken()).isEqualTo(7);
                    assertThat(attempt.getTotalToken()).isEqualTo(18);
                });
    }

    @Test
    void aSecretInThePromptNeverReachesTheProvider() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 200, chatAnswer("an answer")));
        // Shaped like a key, and deliberately not one: nothing in this repository
        // needs a real credential to prove the masking works.
        String keyShaped = "sk-" + "0123456789abcdefghijklmn";

        gateway.chat("Deploy with " + keyShaped + " please", null, AiUseCase.IMPLEMENTATION_GUIDANCE,
                fixture.revisionContext());

        assertThat(BODIES).singleElement().satisfies(body -> {
            assertThat(body).doesNotContain(keyShaped);
            assertThat(body).contains(PromptSanitizer.MASK);
        });
    }

    // ------------------------------------------------------- malformed answers

    @Test
    void anAnswerThatIsNotJsonFailsInsteadOfBecomingAnEmptyResult() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 200, "{\"choices\": [ truncated"));

        assertThatThrownBy(() -> gateway.chat("prompt", null, AiUseCase.REQUIREMENT_QUALITY, fixture.revisionContext()))
                .isInstanceOf(ApiException.class);

        // Not retried: asking again cannot repair a broken body, and the one
        // format repair ARCHITECTURE.md 3.5 allows is review's budget, not this one.
        assertThat(REQUESTS).hasValue(1);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(AiCallStatus.FAILED);
                    assertThat(attempt.getError()).isEqualTo("the answer was not JSON");
                });
    }

    @Test
    void aWellFormedAnswerWithoutContentIsAFailureToo() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 200, "{\"choices\":[]}"));

        assertThatThrownBy(() -> gateway.chat("prompt", null, AiUseCase.REQUIREMENT_QUALITY, fixture.revisionContext()))
                .isInstanceOf(ApiException.class);

        assertThat(REQUESTS).hasValue(1);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt ->
                        assertThat(attempt.getError()).isEqualTo("the answer carried no message content"));
    }

    // -------------------------------------------------------------- embeddings

    @Test
    void embedReturnsOneVectorPerInputAndRecordsTheCallersModel() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 200,
                "{\"data\":[{\"embedding\":[0.5,-0.25]},{\"embedding\":[0.125,0.0625]}],"
                        + "\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"));

        List<float[]> vectors = gateway.embed(List.of("first", "second"), EMBEDDING_MODEL,
                AiCallContext.ofProject(fixture.project));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.5f, -0.25f);
        assertThat(vectors.get(1)).containsExactly(0.125f, 0.0625f);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getUseCase()).isEqualTo(AiUseCase.EMBEDDING);
                    assertThat(attempt.getModel()).isEqualTo(EMBEDDING_MODEL);
                    assertThat(attempt.getStatus()).isEqualTo(AiCallStatus.SUCCESS);
                    assertThat(attempt.getRequirementId()).isNull();
                });
    }

    @Test
    void embedRefusesAnAnswerWithFewerVectorsThanInputs() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 200, "{\"data\":[{\"embedding\":[0.5,-0.25]}]}"));

        // Silently returning one vector would leave a chunk embedded with its
        // neighbour's meaning, which no later test could detect.
        assertThatThrownBy(() -> gateway.embed(List.of("first", "second"), EMBEDDING_MODEL,
                AiCallContext.ofProject(fixture.project))).isInstanceOf(ApiException.class);

        assertThat(REQUESTS).hasValue(1);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt ->
                        assertThat(attempt.getError()).isEqualTo("the answer carried 1 embeddings for 2 inputs"));
    }

    // ------------------------------------------------------------- the payload

    @Test
    void anAttemptSurvivesTheCallersRollback() {
        Fixture fixture = new Fixture();
        BEHAVIOUR.set(exchange -> respond(exchange, 400, "{\"error\":\"your request is wrong\"}"));
        TransactionTemplate caller = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> caller.executeWithoutResult(transaction -> {
            jdbc.update("update project set name = 'rolled back' where id = ?", fixture.project);
            gateway.chat("prompt", null, AiUseCase.REQUIREMENT_QUALITY, fixture.revisionContext());
        })).isInstanceOf(ApiException.class);

        // The caller's own write is gone, which is what makes the next assertion
        // mean something: the record of why it failed outlived the transaction
        // that failed. Without that, "故障定位" would be erased by the rollback it
        // is supposed to explain (ARCHITECTURE.md 2.1).
        assertThat(jdbc.queryForObject("select name from project where id = ?", String.class, fixture.project))
                .isNotEqualTo("rolled back");
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project)).hasSize(1);
    }

    @Test
    void neitherThePromptNorTheAnswerCanReachTheApplicationLog() {
        Fixture fixture = new Fixture();
        String promptMarker = "PROMPT-MARKER-4f21";
        String answerMarker = "ANSWER-MARKER-4f21";
        BEHAVIOUR.set(exchange -> respond(exchange, 200, "{\"note\":\"" + answerMarker + "\"}"));

        Throwable thrown = catchThrowable(() -> gateway.chat(promptMarker, null,
                AiUseCase.IMPLEMENTATION_GUIDANCE, fixture.revisionContext()));

        // ApiExceptionHandler logs a 5xx with exactly this stack trace, and it is
        // the only logger in the backend. If a payload is not in here, it cannot
        // reach the application log at all.
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(stackTraceOf(thrown)).doesNotContain(promptMarker, answerMarker);
        assertThat(callLogs.findByProjectIdOrderByIdAsc(fixture.project))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getError()).doesNotContain(promptMarker, answerMarker));
    }

    // ----------------------------------------------------------------- helpers

    private static String stackTraceOf(Throwable thrown) {
        StringWriter trace = new StringWriter();
        thrown.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

    private static String chatAnswer(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream answer = exchange.getResponseBody()) {
            answer.write(bytes);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A project, a requirement and a revision, so the call record's foreign keys have real targets. */
    private final class Fixture {

        private final long project;
        private final long requirement;
        private final long revision;

        private Fixture() {
            Long owner = jdbc.queryForObject(
                    "insert into user_account (username, password_hash) values (?, 'x') returning id",
                    Long.class, "ai-" + COUNTER.incrementAndGet());
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "ai-project-" + COUNTER.incrementAndGet(), owner);
            this.requirement = jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                    Long.class, project);
            this.revision = jdbc.queryForObject(
                    "insert into requirement_revision (project_id, requirement_id, seq, title, created_by) "
                            + "values (?, ?, 1, 'title', ?) returning id",
                    Long.class, project, requirement, owner);
        }

        private AiCallContext revisionContext() {
            return AiCallContext.ofRevision(project, requirement, revision);
        }
    }
}
