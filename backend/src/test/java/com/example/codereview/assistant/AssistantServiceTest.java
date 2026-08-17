package com.example.codereview.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.ai.TokenUsage;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.context.ContextBuilder;
import com.example.codereview.context.ContextScene;
import com.example.codereview.requirement.RequirementEntity;
import com.example.codereview.requirement.RequirementRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AssistantServiceTest {

    @Test
    void concurrentCapacityIsBoundedBeforeOpeningAnotherStream() throws Exception {
        ProjectAuthorization authorization = mock(ProjectAuthorization.class);
        RequirementRepository requirements = mock(RequirementRepository.class);
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        AssistantPromptAssembler promptAssembler = mock(AssistantPromptAssembler.class);
        AiCallLogService logs = mock(AiCallLogService.class);
        RequirementEntity requirement = new RequirementEntity(3L, 9L, "title", "", "", "HIGH", 1L);
        ContextBuilder.ContextBundle context = new ContextBuilder.ContextBundle(
                List.of(), 0,
                new ContextBuilder.RequirementSnapshot("REQ-9", "REQ-9", "title", "", "", "DRAFT", List.of()),
                List.of(), List.of(new ContextBuilder.Source("REQ-9", "REQUIREMENT", "title", "REQ-9")),
                List.of(), List.of());
        AssistantPrompt prompt = new AssistantPrompt("system", "user", 10, List.of("REQ-9"), List.of());
        when(requirements.findByIdAndProjectId(8L, 3L)).thenReturn(Optional.of(requirement));
        when(contextBuilder.build(ContextScene.ASSISTANT, 3L, ContextBuilder.Refs.assistant(4L, 8L)))
                .thenReturn(context);
        when(promptAssembler.assemble(context, List.of(), "question")).thenReturn(prompt);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AssistantModelClient model = (ignoredPrompt, onDelta, cancelled) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", ex);
            }
            return TokenUsage.none();
        };
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        try {
            AssistantService service = new AssistantService(authorization, requirements, contextBuilder,
                    promptAssembler, model, logs, executor, true, 30000, 24000, 1);
            AssistantDtos.StreamRequest request = new AssistantDtos.StreamRequest("question", List.of());

            service.stream(3L, 8L, 4L, request);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.stream(3L, 8L, 4L, request))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
