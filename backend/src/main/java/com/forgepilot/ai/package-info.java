/**
 * 只承载 OpenAI 兼容的 chat / embed 协议及其调用记录，别的一概不放。
 * ARCHITECTURE.md 1.2 把业务 Prompt、Agent 编排和自动决策挡在本包之外；
 * 1.3 规定它只能依赖 {@code common} —— 这正是
 * {@link com.forgepilot.ai.AiCallContext} 传不透明 id 而非业务类型的原因。
 */
package com.forgepilot.ai;
