package com.example.codereview.agent.run;

/**
 * Published inside the transaction that persists a brand-new Agent Run, so kickoff wiring can
 * schedule the first step atomically with run creation (outbox pattern: both rows commit or
 * neither does). Replayed deliveries reuse the existing run and must not publish this event.
 */
public record AgentRunCreatedEvent(Long agentRunId, String triggerKey) {
}
