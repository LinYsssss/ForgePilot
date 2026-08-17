package com.example.codereview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.codereview.project.ProjectService;
import com.example.codereview.review.ReviewTaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiCallLogServiceAssistantTest {

    @Test
    void recordsAssistantSuccessAndFailureWithoutMessageBodies() {
        AiCallLogRepository repository = mock(AiCallLogRepository.class);
        AiCallLogService service = new AiCallLogService(repository, mock(ProjectService.class),
                mock(ReviewTaskRepository.class), "mock", "mock-assistant", "mock", "mock-embedding");

        service.assistantSuccess(7L, 120, 45, new TokenUsage(10, 5, 15), 33);
        service.assistantFailed(7L, 90, 20, "SocketTimeoutException");

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AiCallLog::getRequestType)
                .containsOnly(AiCallLogService.ASSISTANT);
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getAllValues().get(0).getTotalTokens()).isEqualTo(15);
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(captor.getAllValues().get(1).getErrorMessage()).isEqualTo("SocketTimeoutException");
    }
}
