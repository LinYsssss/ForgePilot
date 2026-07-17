package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPatchModelServiceTest {

    private final AgentModelCallAuditService audit = mock(AgentModelCallAuditService.class);
    private final AgentModelClient client = mock(AgentModelClient.class);
    private final AgentPatchModelService service = new AgentPatchModelService(
            new ObjectMapper(), audit, 16_384
    );
    private final PromptEnvelope prompt = new PromptEnvelope(
            "policy", "patch", "diff", "", "", "", "{}", "review-v1", null,
            "patch-candidate-v1", List.of(), List.of()
    );

    @Test
    void acceptsOnlySchemaValidUnifiedDiffEnvelope() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response(
                "{\"unifiedDiff\":\"diff --git a/src/App.java b/src/App.java\\n"
                        + "--- a/src/App.java\\n+++ b/src/App.java\\n@@ -1 +1 @@\\n-old\\n+new\\n\"}"
        ));

        assertThat(service.generate(1L, client, prompt).response().unifiedDiff())
                .startsWith("diff --git");
    }

    @Test
    void rejectsMarkdownOrUnknownPatchFields() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("{\"patch\":\"unsafe\"}"));

        assertThatThrownBy(() -> service.generate(1L, client, prompt))
                .isInstanceOf(AgentStepExecutionException.class);
    }

    private AgentModelClient.ModelResponse response(String content) {
        return new AgentModelClient.ModelResponse("fixture", "fixture", content, 100, 50, "STOP", 10);
    }
}
