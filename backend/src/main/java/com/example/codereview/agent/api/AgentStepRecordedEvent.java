package com.example.codereview.agent.api;

/**
 * 在持久化 Agent 步骤变更的那个事务**内部**发布,好让 {@link AgentEventService} 等到事务提交之后
 * 才把变更扇出给在线的 SSE 订阅者。事件只携带标识符;订阅者自己回数据库重读权威行。
 */
public record AgentStepRecordedEvent(Long agentRunId, int sequenceNo) {

    public AgentStepRecordedEvent {
        if (agentRunId == null) {
            throw new IllegalArgumentException("agentRunId must not be null");
        }
    }
}
