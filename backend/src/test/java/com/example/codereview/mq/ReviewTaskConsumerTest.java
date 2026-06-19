package com.example.codereview.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.codereview.review.ReviewProcessor;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewTaskConsumerTest {

    @Mock
    private ReviewProcessor reviewProcessor;
    @Mock
    private ReviewTaskPublisher publisher;
    @Mock
    private MqLogService mqLogService;

    @Test
    void retryableFailurePublishesDelayedRetry() {
        ReviewTaskMessage message = message(1);
        org.mockito.Mockito.doThrow(new RuntimeException("temporary"))
                .when(reviewProcessor).process(message.taskId());
        ReviewTaskConsumer consumer = new ReviewTaskConsumer(reviewProcessor, publisher, mqLogService, 3);

        consumer.consume(message);

        verify(mqLogService).failed(message, "temporary");
        verify(publisher).publishDelayed(argThat(retry ->
                retry.messageId().equals(message.messageId())
                        && retry.taskId().equals(message.taskId())
                        && retry.retryCount() == 2));
        verify(publisher, never()).publishDead(any(), anyString());
        verify(reviewProcessor, never()).markDead(any(), anyString());
    }

    @Test
    void exhaustedFailureMarksTaskDeadAndPublishesDeadLetter() {
        ReviewTaskMessage message = message(3);
        org.mockito.Mockito.doThrow(new RuntimeException("permanent"))
                .when(reviewProcessor).process(message.taskId());
        ReviewTaskConsumer consumer = new ReviewTaskConsumer(reviewProcessor, publisher, mqLogService, 3);

        consumer.consume(message);

        verify(reviewProcessor).markDead(message.taskId(), "permanent");
        verify(publisher).publishDead(message, "permanent");
        verify(publisher, never()).publishDelayed(any());
    }

    private ReviewTaskMessage message(int retryCount) {
        return new ReviewTaskMessage("message-1", 1L, 2L, "abc", retryCount, Instant.EPOCH);
    }
}
