package com.example.codereview.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String REVIEW_EXCHANGE = "review.exchange";
    public static final String REVIEW_TASK_QUEUE = "code.review.task.queue";
    public static final String REVIEW_DEAD_QUEUE = "code.review.dead.queue";
    public static final String REVIEW_TASK_ROUTING_KEY = "review.task";
    public static final String REVIEW_DEAD_ROUTING_KEY = "review.dead";

    @Bean
    DirectExchange reviewExchange() {
        return new DirectExchange(REVIEW_EXCHANGE, true, false);
    }

    @Bean
    Queue reviewTaskQueue() {
        return new Queue(REVIEW_TASK_QUEUE, true);
    }

    @Bean
    Queue reviewDeadQueue() {
        return new Queue(REVIEW_DEAD_QUEUE, true);
    }

    @Bean
    Binding reviewTaskBinding(Queue reviewTaskQueue, DirectExchange reviewExchange) {
        return BindingBuilder.bind(reviewTaskQueue).to(reviewExchange).with(REVIEW_TASK_ROUTING_KEY);
    }

    @Bean
    Binding reviewDeadBinding(Queue reviewDeadQueue, DirectExchange reviewExchange) {
        return BindingBuilder.bind(reviewDeadQueue).to(reviewExchange).with(REVIEW_DEAD_ROUTING_KEY);
    }
}
