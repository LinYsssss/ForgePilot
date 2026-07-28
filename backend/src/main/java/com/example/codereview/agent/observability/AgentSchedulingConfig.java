package com.example.codereview.agent.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling infrastructure for the Agent background loops — the outbox drain and
 * the step recovery watchdog.
 *
 * <p>Gating it behind a flag that defaults to off keeps test contexts free of background threads
 * that would otherwise race their assertions, and stops a developer without a broker from getting a
 * connection error every second. Production turns it on; without it, Agent runs do not advance on
 * their own.
 */
@Configuration
@ConditionalOnProperty(value = "app.agent.scheduling.enabled", havingValue = "true")
@EnableScheduling
public class AgentSchedulingConfig {
}
