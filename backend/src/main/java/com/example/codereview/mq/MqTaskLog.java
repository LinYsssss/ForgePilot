package com.example.codereview.mq;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mq_task_log")
public class MqTaskLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long taskId;

    @Column(nullable = false, length = 128)
    private String messageId;

    @Column(nullable = false, length = 128)
    private String exchangeName;

    @Column(nullable = false, length = 128)
    private String routingKey;

    @Column(nullable = false, length = 128)
    private String queueName;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private int retryCount;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    protected MqTaskLog() {
    }

    public MqTaskLog(Long taskId, String messageId, String exchangeName, String routingKey, String queueName,
                     String payload, String status, int retryCount, String errorMessage) {
        this.taskId = taskId;
        this.messageId = messageId;
        this.exchangeName = exchangeName;
        this.routingKey = routingKey;
        this.queueName = queueName;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getExchangeName() {
        return exchangeName;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getQueueName() {
        return queueName;
    }

    public String getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
