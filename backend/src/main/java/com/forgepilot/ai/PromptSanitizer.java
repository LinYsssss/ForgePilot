package com.forgepilot.ai;

import java.util.regex.Pattern;

/**
 * The last thing applied to anything on its way to a provider.
 *
 * <p>ARCHITECTURE.md 4.3: requirement text, documents, PR titles and code
 * comments "全部是不可信数据 … 发送前做敏感信息脱敏与预算裁剪". So this masks
 * key-shaped strings and cuts the payload to budget, and does nothing else. It
 * is a pure function (LEGACY-MIGRATION-MATRIX.md, "纯函数"): it never logs,
 * never touches the database and never throws — a document is not rejected for
 * containing something that looks like a token, it is sent without it.
 *
 * <p>Rejecting bad input is a different job with a different owner:
 * {@code KnowledgeUploadValidator} refuses invalid UTF-8, NUL bytes, lone
 * surrogates and oversized uploads. Putting upload policy in {@code ai} would
 * break ARCHITECTURE.md 1.2.
 */
public final class PromptSanitizer {

    static final String MASK = "[redacted]";

    /**
     * Credential shapes, not credential values: nothing here needs to know a
     * real key, which is what lets the tests run with none in existence.
     */
    private static final Pattern SECRETS = Pattern.compile(
            "sk-[A-Za-z0-9_-]{16,}"                                            // OpenAI-compatible keys
                    + "|gh[pousr]_[A-Za-z0-9]{20,}"                            // GitHub tokens
                    + "|github_pat_[A-Za-z0-9_]{20,}"
                    + "|AKIA[0-9A-Z]{16}"                                      // AWS access key ids
                    + "|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"  // JWTs
                    + "|[Bb]earer [A-Za-z0-9._~+/=-]{16,}");

    private PromptSanitizer() {
    }

    /** Masks every credential shape, then cuts the result to {@code maxChars}. */
    public static String sanitize(String untrusted, int maxChars) {
        return truncate(SECRETS.matcher(untrusted).replaceAll(MASK), maxChars);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        // Never cut between the halves of a surrogate pair. The result would not
        // be valid UTF-16, and encoding it replaces the character with '?' —
        // silent corruption of exactly the kind Phase 4 has to fail on instead.
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
