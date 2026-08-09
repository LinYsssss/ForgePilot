package com.example.codereview.agent.queue;

import com.example.codereview.agent.run.AgentRunCreatedEvent;
import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 新建 Agent Run 的首步派发。此前这一环不存在:webhook 落库 RECEIVED 后,
 * 步骤调度只会在"上一步完成"时排下一步,首步无人排入,整条守门链路从未真正起跑
 * (WebhookAgentRunService 注释里的 "downstream scheduling" 是空指)。
 *
 * <p>BEFORE_COMMIT:首步的 step 行与 outbox 事件和 Run 创建同一事务提交,
 * 要么都在、要么都不在,不存在"有 Run 无首步"的中间态。重放投递复用既有 Run,
 * 不发事件,不会重复排步(schedule 自身另有幂等护栏)。
 */
@Component
public class AgentRunKickoffListener {

    private final AgentStepPublisher publisher;

    public AgentRunKickoffListener(AgentStepPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRunCreated(AgentRunCreatedEvent event) {
        publisher.schedule(
                event.agentRunId(), 1, AgentRunStatus.PREPARING_REPOSITORY,
                "webhook:" + event.triggerKey());
    }
}
