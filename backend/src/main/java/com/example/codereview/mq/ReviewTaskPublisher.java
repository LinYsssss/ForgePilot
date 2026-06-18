package com.example.codereview.mq;

import com.example.codereview.config.RabbitMqConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MqLogService mqLogService;

    public ReviewTaskPublisher(RabbitTemplate rabbitTemplate, MqLogService mqLogService) {
        this.rabbitTemplate = rabbitTemplate;
        this.mqLogService = mqLogService;
    }

    public void publish(ReviewTaskMessage message) {
        mqLogService.published(message, RabbitMqConfig.REVIEW_TASK_ROUTING_KEY);
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.REVIEW_EXCHANGE,
                RabbitMqConfig.REVIEW_TASK_ROUTING_KEY,
                message
        );
    }

    public void publishDead(ReviewTaskMessage message, String error) {
        mqLogService.dead(message, error);
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.REVIEW_EXCHANGE,
                RabbitMqConfig.REVIEW_DEAD_ROUTING_KEY,
                message
        );
    }

    public void publishDelayed(ReviewTaskMessage message) {
        mqLogService.delayed(message);
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.REVIEW_EXCHANGE,
                RabbitMqConfig.REVIEW_DELAY_ROUTING_KEY,
                message
        );
    }
}
