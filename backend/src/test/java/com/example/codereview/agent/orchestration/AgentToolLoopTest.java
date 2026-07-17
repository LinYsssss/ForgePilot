package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentToolLoopTest {

    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentToolLoop loop = new AgentToolLoop(tools, mapper);

    @Test
    void executesPlannedRequestsWithModelRequestIdAsIdempotencyKey() {
        JsonNode arguments = mapper.createObjectNode().put("path", "src/Main.java");
        doReturn(ToolResult.success("diff")).when(tools).execute(any(), any(), any());

        var results = loop.execute(
                new AgentToolLoop.LoopContext(7L, 9L, "trace", false),
                List.of(new AgentToolLoop.ToolRequest("call-1", "git.diff", arguments)),
                new AgentToolLoop.LoopPolicy(Set.of("git.diff"), Set.of("git.diff"), 2, 2048),
                () -> false
        );

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.requestId()).isEqualTo("call-1");
            assertThat(result.status()).isEqualTo(AgentToolLoop.ToolStatus.SUCCESS);
        });
        verify(tools).execute(
                org.mockito.ArgumentMatchers.eq("git.diff"),
                org.mockito.ArgumentMatchers.eq(arguments),
                org.mockito.ArgumentMatchers.argThat(context ->
                        context.invocationKey().equals("agent:7:model-tool:call-1"))
        );
    }

    @Test
    void rejectsInjectedUnknownUnplannedTraversalAndOversizedRequestsBeforeExecution() {
        var requests = List.of(
                request("1", "scm.comment", mapper.createObjectNode()),
                request("2", "git.diff", mapper.createObjectNode().put("command", "whoami")),
                request("3", "git.diff", mapper.createObjectNode().put("path", "../../secret")),
                request("4", "git.diff", mapper.createObjectNode().put("value", "x".repeat(3000)))
        );

        var results = loop.execute(
                new AgentToolLoop.LoopContext(7L, 9L, "trace", false),
                requests,
                new AgentToolLoop.LoopPolicy(Set.of("git.diff"), Set.of("git.diff"), 4, 256),
                () -> false
        );

        assertThat(results).extracting(AgentToolLoop.ToolResultEnvelope::status)
                .containsOnly(AgentToolLoop.ToolStatus.POLICY_REJECTED);
        verify(tools, never()).execute(any(), any(), any(ToolContext.class));
    }

    @Test
    void boundsCallsRejectsDuplicateRequestIdsAndStopsOnCancellation() {
        doReturn(ToolResult.success("ok")).when(tools).execute(any(), any(), any());
        var requests = List.of(
                request("same", "git.diff", mapper.createObjectNode()),
                request("same", "git.diff", mapper.createObjectNode()),
                request("third", "git.diff", mapper.createObjectNode())
        );

        var results = loop.execute(
                new AgentToolLoop.LoopContext(7L, 9L, "trace", false),
                requests,
                new AgentToolLoop.LoopPolicy(Set.of("git.diff"), Set.of("git.diff"), 2, 256),
                () -> false
        );

        assertThat(results).extracting(AgentToolLoop.ToolResultEnvelope::status)
                .containsExactly(
                        AgentToolLoop.ToolStatus.SUCCESS,
                        AgentToolLoop.ToolStatus.POLICY_REJECTED,
                        AgentToolLoop.ToolStatus.POLICY_REJECTED
                );

        var canceled = loop.execute(
                new AgentToolLoop.LoopContext(7L, 9L, "trace", false),
                List.of(request("cancel", "git.diff", mapper.createObjectNode())),
                new AgentToolLoop.LoopPolicy(Set.of("git.diff"), Set.of("git.diff"), 1, 256),
                () -> true
        );
        assertThat(canceled).singleElement()
                .extracting(AgentToolLoop.ToolResultEnvelope::status)
                .isEqualTo(AgentToolLoop.ToolStatus.CANCELED);
    }

    private AgentToolLoop.ToolRequest request(String id, String name, JsonNode arguments) {
        return new AgentToolLoop.ToolRequest(id, name, arguments);
    }
}
