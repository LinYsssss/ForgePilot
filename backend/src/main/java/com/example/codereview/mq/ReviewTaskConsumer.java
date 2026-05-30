package com.example.codereview.mq;

import com.example.codereview.config.RabbitMqConfig;
import com.example.codereview.review.ReviewProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskConsumer {

    private final ReviewProcessor reviewProcessor;
    private final ReviewTaskPublisher publisher;
    private final MqLogService mqLogService;

    public ReviewTaskConsumer(ReviewProcessor reviewProcessor, ReviewTaskPublisher publisher, MqLogService mqLogService) {
        this.reviewProcessor = reviewProcessor;
        this.publisher = publisher;
        this.mqLogService = mqLogService;
    }

    @RabbitListener(queues = RabbitMqConfig.REVIEW_TASK_QUEUE)
    public void consume(ReviewTaskMessage message) {
        try {
            reviewProcessor.process(message.taskId());
            mqLogService.consumed(message);
        } catch (RuntimeException ex) {
            mqLogService.failed(message, ex.getMessage());
            if (message.retryCount() >= 3) {
                reviewProcessor.markDead(message.taskId(), ex.getMessage());
                publisher.publishDead(message, ex.getMessage());
            } else {
                publisher.publish(message.nextRetry());
            }
        }
    }
}
