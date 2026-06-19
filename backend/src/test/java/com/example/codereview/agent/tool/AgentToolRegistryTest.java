package com.example.codereview.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentToolRegistryTest {

    private final ToolInvocationRepository invocations = mock(ToolInvocationRepository.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ToolContext context = new ToolContext(1L, 2L, "invoke-1", false, "trace-1");

    @Test
    void rejectsDuplicateAndUnknownTools() {
        AgentTool<TestInput, TestOutput> tool = echoTool(ToolRiskLevel.READ_ONLY, new AtomicInteger());

        assertThatThrownBy(() -> registry(List.of(tool, tool)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");

        assertThatThrownBy(() -> registry(List.of(tool))
                .execute("missing", mapper.createObjectNode(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void rejectsApprovalRequiredAndMalformedInputs() {
        AgentTool<TestInput, TestOutput> tool = echoTool(
                ToolRiskLevel.WRITE_REQUIRES_APPROVAL,
                new AtomicInteger()
        );
        AgentToolRegistry registry = registry(List.of(tool));

        assertThatThrownBy(() -> registry.execute(
                "echo",
                mapper.valueToTree(new TestInput("safe")),
                context
        )).hasMessageContaining("approval");

        ToolContext approved = new ToolContext(1L, 2L, "invoke-2", true, "trace-1");
        assertThatThrownBy(() -> registry.execute(
                "echo",
                mapper.createObjectNode().put("unknown", "field"),
                approved
        )).hasMessageContaining("Malformed");
    }

    @Test
    void rejectsRawCommandFields() {
        AgentToolRegistry registry = registry(List.of(echoTool(
                ToolRiskLevel.READ_ONLY,
                new AtomicInteger()
        )));

        assertThatThrownBy(() -> registry.execute(
                "echo",
                mapper.createObjectNode().put("value", "safe").put("command", "rm -rf /"),
                context
        )).hasMessageContaining("command");
    }

    @Test
    void executesTypedToolOnceAndPersistsSanitizedResult() {
        AtomicInteger executions = new AtomicInteger();
        when(invocations.findByInvocationKey("invoke-1")).thenReturn(Optional.empty());
        when(invocations.save(any(ToolInvocation.class)))
                .thenAnswer(call -> call.getArgument(0));

        ToolResult<?> result = registry(List.of(echoTool(ToolRiskLevel.READ_ONLY, executions)))
                .execute("echo", mapper.valueToTree(new TestInput("hello")), context);

        assertThat(result.success()).isTrue();
        assertThat(mapper.valueToTree(result.data()).get("value").asText()).isEqualTo("HELLO");
        assertThat(executions).hasValue(1);
        verify(invocations, times(2)).save(any(ToolInvocation.class));
    }

    @Test
    void completedInvocationIsIdempotent() {
        ToolInvocation existing = ToolInvocation.started(
                "invoke-1", 1L, 2L, "echo", "{}", "trace-1"
        );
        existing.succeed("{\"value\":\"CACHED\"}", 5);
        when(invocations.findByInvocationKey("invoke-1")).thenReturn(Optional.of(existing));

        AtomicInteger executions = new AtomicInteger();
        ToolResult<?> result = registry(List.of(echoTool(ToolRiskLevel.READ_ONLY, executions)))
                .execute("echo", mapper.valueToTree(new TestInput("hello")), context);

        assertThat(result.success()).isTrue();
        assertThat(mapper.valueToTree(result.data()).get("value").asText()).isEqualTo("CACHED");
        assertThat(executions).hasValue(0);
        verify(invocations, never()).save(any(ToolInvocation.class));
    }

    private AgentToolRegistry registry(List<AgentTool<?, ?>> tools) {
        return new AgentToolRegistry(tools, mapper, invocations, 4_096, 8_192);
    }

    private AgentTool<TestInput, TestOutput> echoTool(
            ToolRiskLevel riskLevel,
            AtomicInteger executions
    ) {
        return new AgentTool<>() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public Class<TestInput> inputType() {
                return TestInput.class;
            }

            @Override
            public ToolRiskLevel riskLevel() {
                return riskLevel;
            }

            @Override
            public ToolResult<TestOutput> execute(ToolContext ignored, TestInput input) {
                executions.incrementAndGet();
                return ToolResult.success(new TestOutput(input.value().toUpperCase()));
            }
        };
    }

    record TestInput(String value) {
    }

    record TestOutput(String value) {
    }
}
