package com.forgepilot.ai;

import java.util.regex.Pattern;

/**
 * 任何内容发往 provider 之前经过的最后一道处理。
 *
 * <p>ARCHITECTURE.md 4.3：需求文本、文档、PR 标题和代码注释“全部是不可信数据 …
 * 发送前做敏感信息脱敏与预算裁剪”。因此本类只做两件事——掩码疑似凭据的字符串、
 * 把载荷裁剪到预算内，此外什么都不做。它是纯函数（LEGACY-MIGRATION-MATRIX.md
 * “纯函数”）：不写日志、不碰数据库、不抛异常——一份文档不会因为“看起来含 token”
 * 而被拒绝，只会去掉那部分后照常发送。
 *
 * <p>拒绝非法输入是另一件事、另有归属：{@code KnowledgeUploadValidator} 负责拒绝
 * 非法 UTF-8、NUL 字节、孤立代理项和超限上传。把上传策略塞进 {@code ai} 会破坏
 * ARCHITECTURE.md 1.2 的模块边界。
 */
public final class PromptSanitizer {

    static final String MASK = "[redacted]";

    /**
     * 匹配的是**凭据的形状**而非凭据的值：这里不需要知道任何真实密钥，
     * 这也正是测试可以在一个真凭据都不存在的环境下运行的原因。
     */
    private static final Pattern SECRETS = Pattern.compile(
            "sk-[A-Za-z0-9_-]{16,}"                                            // OpenAI 兼容密钥
                    + "|gh[pousr]_[A-Za-z0-9]{20,}"                            // GitHub token
                    + "|github_pat_[A-Za-z0-9_]{20,}"
                    + "|AKIA[0-9A-Z]{16}"                                      // AWS access key id
                    + "|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"  // JWT
                    + "|[Bb]earer [A-Za-z0-9._~+/=-]{16,}");

    private PromptSanitizer() {
    }

    /** 先掩码全部凭据形状，再把结果裁剪到 {@code maxChars}。 */
    public static String sanitize(String untrusted, int maxChars) {
        return truncate(SECRETS.matcher(untrusted).replaceAll(MASK), maxChars);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        // 绝不能从代理对（surrogate pair）中间切断。那样得到的不是合法 UTF-16，
        // 编码时该字符会被替换成 '?' —— 正是 Phase 4 要求“显式失败”而非
        // 静默损坏的那类问题。
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
