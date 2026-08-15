package com.example.codereview.ai;

/**
 * OpenAI 兼容 chat completion 上报的 token 用量。按每次 AI 调用采集,让平台能暴露真实的
 * 模型成本(token 数)而不只是字符数。Mock / 规则引擎审查上报 {@link #none()},因为它们
 * 根本不消耗模型 token。
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        // 有些 provider 不返回 total_tokens;缺失时用两部分相加推导。
        totalTokens = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
    }

    public static TokenUsage none() {
        return new TokenUsage(0, 0, 0);
    }

    /** 把分片审查各次调用的用量累加,好让一个多次调用的任务报出总成本。 */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens
        );
    }
}
