package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentFindingModelServiceTest {

    private final AgentModelCallAuditService audit = mock(AgentModelCallAuditService.class);
    private final AgentModelClient client = mock(AgentModelClient.class);
    private final AgentFindingModelService service = new AgentFindingModelService(
            new ObjectMapper(), audit, 16_384
    );
    private final PromptEnvelope prompt = new PromptEnvelope(
            "policy", "findings", "diff", "", "", "knowledge", "{}",
            "review-v1", null, "finding-candidates-v1", List.of(), List.of("rule#chunk-1")
    );

    @Test
    void acceptsSchemaValidFindingWithSuppliedCitation() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[{"severity":"HIGH","category":"security","title":"SQL leak",
                "description":"Connection is not closed","filePath":"src/Main.java",
                "lineStart":10,"lineEnd":10,"symbol":"run","ruleId":"JAVA-1",
                "citationIds":["rule#chunk-1"]}]}
                """));

        AgentFindingModelService.Result result = service.generate(
                1L, client, prompt, Set.of("rule#chunk-1")
        );

        assertThat(result.response().findings()).singleElement()
                .extracting(FindingModelResponse.ModelFinding::title)
                .isEqualTo("SQL leak");
    }

    @Test
    void rejectsFabricatedOrDuplicateCitation() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[{"severity":"HIGH","category":"security","title":"Claim",
                "description":"Description","filePath":"src/Main.java","lineStart":10,
                "lineEnd":10,"symbol":"run","ruleId":"X",
                "citationIds":["fabricated"]}]}
                """));

        assertThatThrownBy(() -> service.generate(1L, client, prompt, Set.of("rule#chunk-1")))
                .isInstanceOf(AgentStepExecutionException.class)
                .satisfies(error -> assertThat(((AgentStepExecutionException) error).getFailureType())
                        .isEqualTo(AgentFailureType.INVALID_MODEL_OUTPUT));
    }

    private AgentModelClient.ModelResponse response(String content) {
        return new AgentModelClient.ModelResponse(
                "fixture", "fixture", content, 100, 50, "STOP", 10
        );
    }
}
