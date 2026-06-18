package com.example.codereview.ai;

/**
 * Token usage reported by an OpenAI-compatible chat completion. Captured per AI call so the
 * platform can surface real model cost (tokens), not just character counts. Mock / rule-engine
 * reviews report {@link #none()} because they consume no model tokens.
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        // Some providers omit total_tokens; derive it from the parts when missing.
        totalTokens = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
    }

    public static TokenUsage none() {
        return new TokenUsage(0, 0, 0);
    }

    /** Sums usage across chunked review calls so a multi-call task reports its total cost. */
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
