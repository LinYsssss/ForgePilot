package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StructuredAgentModelServiceTest {

    private final ModelOutputValidator validator = mock(ModelOutputValidator.class);
    private final AgentModelCallAuditService audit = mock(AgentModelCallAuditService.class);
    private final AgentModelClient client = mock(AgentModelClient.class);
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final StructuredAgentModelService service =
            new StructuredAgentModelService(validator, audit);

    @BeforeEach
    void captureSaves() {
        when(audit.save(any(AgentModelCall.class))).thenAnswer(invocation -> {
            AgentModelCall call = invocation.getArgument(0);
            snapshots.add(Snapshot.from(call));
            return call;
        });
        when(client.provider()).thenReturn("openai-compatible");
        when(client.model()).thenReturn("review-model");
    }

    @Test
    void persistsSuccessfulGenerationMetadataAndResponseHash() {
        when(client.generate(any())).thenReturn(response(
                "{\"summary\":\"ok\",\"plan\":[]}", 10, 3, "STOP", 41
        ));
        when(validator.validate(any(), eq(false), any(), any()))
                .thenReturn(validResult("ok"));

        service.generate(7L, client, prompt(), false);

        Snapshot completed = lastSnapshot("GENERATE");
        assertThat(completed.status()).isEqualTo("VALID");
        assertThat(completed.provider()).isEqualTo("openai-compatible");
        assertThat(completed.model()).isEqualTo("review-model");
        assertThat(completed.inputTokens()).isEqualTo(10);
        assertThat(completed.outputTokens()).isEqualTo(3);
        assertThat(completed.finishReason()).isEqualTo("STOP");
        assertThat(completed.latencyMs()).isEqualTo(41);
        assertThat(completed.responseHash()).matches("[0-9a-f]{64}");
        assertThat(completed.promptVersion()).isEqualTo("prompt-v1");
        assertThat(completed.schemaVersion()).isEqualTo("schema-v1");
        assertThat(completed.promptHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void persistsRepairAsASecondModelCallWithItsOwnTokens() {
        when(client.generate(any())).thenReturn(response("{broken", 8, 2, "STOP", 20));
        when(client.repairJson(any(), any())).thenReturn(response(
                "{\"summary\":\"fixed\",\"plan\":[]}", 5, 4, "STOP", 15
        ));
        when(validator.validate(any(), eq(false), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<String, String> repair = invocation.getArgument(3);
            assertThat(repair.apply("{broken")).contains("\"summary\":\"fixed\"");
            return validResult("fixed");
        });

        service.generate(8L, client, prompt(), false);

        Snapshot generation = lastSnapshot("GENERATE");
        Snapshot repair = lastSnapshot("REPAIR");
        assertThat(generation.status()).isEqualTo("INVALID");
        assertThat(generation.failureReason()).contains("repair");
        assertThat(repair.status()).isEqualTo("VALID");
        assertThat(repair.inputTokens()).isEqualTo(5);
        assertThat(repair.outputTokens()).isEqualTo(4);
    }

    @Test
    void persistsFailedProviderAttemptBeforeRethrowing() {
        when(client.generate(any())).thenThrow(
                new AiCallTransientException("transient provider failure", new RuntimeException())
        );

        assertThatThrownBy(() -> service.generate(9L, client, prompt(), false))
                .isInstanceOf(AiCallTransientException.class);

        Snapshot failed = lastSnapshot("GENERATE");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason())
                .contains("AiCallTransientException")
                .doesNotContain("transient provider failure");
        assertThat(failed.responseHash()).isEqualTo("0".repeat(64));
    }

    private ModelOutputValidator.ValidationResult validResult(String summary) {
        return new ModelOutputValidator.ValidationResult(
                true,
                new StructuredModelResponse(summary, List.of()),
                null,
                null
        );
    }

    private AgentModelClient.ModelResponse response(
            String content,
            long inputTokens,
            long outputTokens,
            String finishReason,
            long latencyMs
    ) {
        return new AgentModelClient.ModelResponse(
                "openai-compatible",
                "review-model",
                content,
                inputTokens,
                outputTokens,
                finishReason,
                latencyMs
        );
    }

    private Snapshot lastSnapshot(String purpose) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.purpose().equals(purpose))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private PromptEnvelope prompt() {
        return new PromptEnvelope(
                "policy",
                "repository",
                "evidence",
                "{\"type\":\"object\"}",
                "prompt-v1",
                "schema-v1"
        );
    }

    private record Snapshot(
            String provider,
            String model,
            String purpose,
            String promptVersion,
            String schemaVersion,
            String promptHash,
            long inputTokens,
            long outputTokens,
            String finishReason,
            long latencyMs,
            String responseHash,
            String status,
            String failureReason
    ) {
        static Snapshot from(AgentModelCall call) {
            return new Snapshot(
                    call.getProvider(),
                    call.getModel(),
                    call.getCallPurpose(),
                    call.getPromptVersion(),
                    call.getSchemaVersion(),
                    call.getPromptHash(),
                    call.getInputTokens(),
                    call.getOutputTokens(),
                    call.getFinishReason(),
                    call.getLatencyMs(),
                    call.getResponseHash(),
                    call.getStatus(),
                    call.getFailureReason()
            );
        }
    }
}
