package com.example.codereview.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.common.web.TraceIdFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AgentStepConsumerCorrelationTest {

    private final AgentStepExecutionService executionService = mock(AgentStepExecutionService.class);
    private final AgentStepConsumer consumer = new AgentStepConsumer(new com.fasterxml.jackson.databind.ObjectMapper(), executionService);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesMessageTraceIdIntoMdcDuringExecutionAndClearsAfter() {
        AtomicReference<String> observed = new AtomicReference<>();
        when(executionService.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            observed.set(MDC.get(TraceIdFilter.TRACE_ID));
            return AgentStepExecutionService.ExecutionOutcome.SUCCEEDED;
        });

        consumer.consume(new AgentStepMessage(1L, 1, 0, "corr-abc123"));

        assertThat(observed.get()).isEqualTo("corr-abc123");
        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
    }

    @Test
    void restoresPreviousTraceIdAfterExecution() {
        MDC.put(TraceIdFilter.TRACE_ID, "outer-trace");
        when(executionService.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(AgentStepExecutionService.ExecutionOutcome.SUCCEEDED);

        consumer.consume(new AgentStepMessage(1L, 1, 0, "inner-trace"));

        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isEqualTo("outer-trace");
    }
}
