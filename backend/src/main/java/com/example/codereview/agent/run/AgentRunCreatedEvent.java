package com.example.codereview.agent.run;

/**
 * 在持久化一个全新 Agent Run 的那个事务**内部**发布,好让启动接线把「排第一个步骤」与
 * 「创建 run」做成原子的(outbox 模式:两行要么一起提交,要么都不提交)。
 * 重放投递会复用既有 run,因此**不得**再发布本事件。
 */
public record AgentRunCreatedEvent(Long agentRunId, String triggerKey) {
}
