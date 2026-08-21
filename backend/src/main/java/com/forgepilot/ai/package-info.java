/**
 * The OpenAI-compatible chat and embed protocol, its call record, and nothing
 * else. ARCHITECTURE.md 1.2 keeps business prompts, agent orchestration and
 * automatic decisions out of this package, and 1.3 lets it depend on
 * {@code common} alone — which is why {@link com.forgepilot.ai.AiCallContext}
 * carries opaque ids instead of business types.
 */
package com.forgepilot.ai;
