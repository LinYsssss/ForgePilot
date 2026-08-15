package com.example.codereview.agent.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 为 Agent 的后台回路——outbox 排空与步骤恢复 watchdog——打开 Spring 的调度基础设施。
 *
 * <p>用一个默认关闭的开关门控它:测试上下文因此不会凭空多出与断言竞态的后台线程,
 * 手上没有 broker 的开发者也不会每秒收到一次连接错误。生产会打开它;不打开的话,
 * Agent run 不会自己往前走。
 *
 * <p>注意「测试默认关闭」只覆盖没主动打开它的上下文:某个测试类一旦显式打开调度,
 * Spring 会缓存复用那个上下文,其后台线程在该类结束后仍在跑——这类测试类必须
 * {@code @DirtiesContext}。
 */
@Configuration
@ConditionalOnProperty(value = "app.agent.scheduling.enabled", havingValue = "true")
@EnableScheduling
public class AgentSchedulingConfig {
}
