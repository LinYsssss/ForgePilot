package com.example.codereview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String REVIEW_EXCHANGE = "review.exchange";
    public static final String REVIEW_TASK_QUEUE = "code.review.task.queue";
    public static final String REVIEW_DELAY_QUEUE = "code.review.delay.queue";
    public static final String REVIEW_DEAD_QUEUE = "code.review.dead.queue";
    public static final String REVIEW_TASK_ROUTING_KEY = "review.task";
    public static final String REVIEW_DELAY_ROUTING_KEY = "review.delay";
    public static final String REVIEW_DEAD_ROUTING_KEY = "review.dead";

    @Bean
    DirectExchange reviewExchange() {
        return new DirectExchange(REVIEW_EXCHANGE, true, false);
    }

    @Bean
    Queue reviewTaskQueue() {
        return QueueBuilder.durable(REVIEW_TASK_QUEUE).build();
    }

    @Bean
    Queue reviewDelayQueue(@Value("${app.review.retry-delay-ms:2000}") long retryDelayMs) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", REVIEW_EXCHANGE);
        args.put("x-dead-letter-routing-key", REVIEW_TASK_ROUTING_KEY);
        args.put("x-message-ttl", retryDelayMs);
        return QueueBuilder.durable(REVIEW_DELAY_QUEUE).withArguments(args).build();
    }

    @Bean
    Queue reviewDeadQueue() {
        return QueueBuilder.durable(REVIEW_DEAD_QUEUE).build();
    }

    @Bean
    Binding reviewTaskBinding(Queue reviewTaskQueue, DirectExchange reviewExchange) {
        return BindingBuilder.bind(reviewTaskQueue).to(reviewExchange).with(REVIEW_TASK_ROUTING_KEY);
    }

    @Bean
    Binding reviewDelayBinding(Queue reviewDelayQueue, DirectExchange reviewExchange) {
        return BindingBuilder.bind(reviewDelayQueue).to(reviewExchange).with(REVIEW_DELAY_ROUTING_KEY);
    }

    @Bean
    Binding reviewDeadBinding(Queue reviewDeadQueue, DirectExchange reviewExchange) {
        return BindingBuilder.bind(reviewDeadQueue).to(reviewExchange).with(REVIEW_DEAD_ROUTING_KEY);
    }

    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
