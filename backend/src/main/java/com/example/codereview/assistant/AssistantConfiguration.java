package com.example.codereview.assistant;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AssistantConfiguration {

    @Bean(name = "assistantTaskExecutor")
    public ThreadPoolTaskExecutor assistantTaskExecutor(
            @Value("${app.assistant.executor.core-size:2}") int coreSize,
            @Value("${app.assistant.executor.max-size:4}") int maxSize,
            @Value("${app.assistant.executor.queue-capacity:8}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("assistant-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
