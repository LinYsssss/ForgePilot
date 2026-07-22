package com.example.reposage.sandbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SandboxRabbitConfig {

    public static final String EXCHANGE = "sandbox.exchange";
    public static final String JOB_QUEUE = "sandbox.job.queue";
    public static final String DEAD_QUEUE = "sandbox.dead.queue";
    public static final String JOB_ROUTING_KEY = "sandbox.job";
    public static final String DEAD_ROUTING_KEY = "sandbox.dead";

    @Bean
    DirectExchange sandboxExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue sandboxJobQueue() {
        return QueueBuilder.durable(JOB_QUEUE)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue sandboxDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    Binding sandboxJobBinding(Queue sandboxJobQueue, DirectExchange sandboxExchange) {
        return BindingBuilder.bind(sandboxJobQueue).to(sandboxExchange).with(JOB_ROUTING_KEY);
    }

    @Bean
    Binding sandboxDeadBinding(Queue sandboxDeadQueue, DirectExchange sandboxExchange) {
        return BindingBuilder.bind(sandboxDeadQueue).to(sandboxExchange).with(DEAD_ROUTING_KEY);
    }

    @Bean
    ObjectMapper objectMapper() {
        // 消息由 backend 的 Spring ObjectMapper 序列化;消费端对未知字段容错,
        // 这样 backend 给 SandboxJob 加字段时不会导致反序列化失败。
        // (SandboxJob 无 java.time 字段,时间用 long epoch 秒,故不涉及日期格式差异。)
        return new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    MessageConverter sandboxMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
