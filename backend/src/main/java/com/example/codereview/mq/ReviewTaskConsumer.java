package com.example.codereview.mq;

import com.example.codereview.config.RabbitMqConfig;
import com.example.codereview.review.ReviewProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskConsumer {

    private final ReviewProcessor reviewProcessor;
    private final ReviewTaskPublisher publisher;
    private final MqLogService mqLogService;
    private final int maxRetry;

    public ReviewTaskConsumer(ReviewProcessor reviewProcessor, ReviewTaskPublisher publisher, MqLogService mqLogService,
                              @Value("${app.review.max-retry:3}") int maxRetry) {
        this.reviewProcessor = reviewProcessor;
        this.publisher = publisher;
        this.mqLogService = mqLogService;
        this.maxRetry = maxRetry;
    }

    @RabbitListener(queues = RabbitMqConfig.REVIEW_TASK_QUEUE)
    public void consume(ReviewTaskMessage message) {
        try {
            reviewProcessor.process(message.taskId());
            mqLogService.consumed(message);
        } catch (RuntimeException ex) {
            mqLogService.failed(message, ex.getMessage());
            if (message.retryCount() >= maxRetry) {
                reviewProcessor.markDead(message.taskId(), ex.getMessage());
                publisher.publishDead(message, ex.getMessage());
            } else {
                publisher.publishDelayed(message.nextRetry());
            }
        }
    }
}
