package com.forgepilot.review;

import java.util.Locale;

/**
 * 一个 Finding 属于哪一类问题（{@code ReviewPrompts} 两个 schema 里的
 * {@code category} 封闭词表）。
 *
 * <p>它和这个包里其他枚举有一处不同，且这处不同是承重的：{@code category}
 * <strong>已经</strong>是 {@code finding_key} 的输入之一（ARCHITECTURE.md 3.6.1），
 * 而进入那个 key 的是模型给出的<em>原始字符串</em>经
 * {@link FindingKeys#normalizeCategory} 折叠之后的结果，不是这个枚举。
 * V9 之前这个值算完 key 就被丢掉了；V9 只是把它也存下来。
 *
 * <p>因此 {@link #of} 复用的必须是同一套归一化：如果落库匹配比 key 更严格，
 * 两条经归一化后属于同一类别、因而共享同一个 {@code finding_key} 的 finding，
 * 就会带着不同的 {@code category} 存进去。
 *
 * <p>词表外的值返回 {@code null} 而不是抛异常，也不落库。{@code ck_finding_category}
 * 是最后一道防线而不是第一道：一个越界值若真的走到约束那里，中止的是**整批**
 * 插入，而不是那一行。
 */
public enum FindingCategory {

    CORRECTNESS,
    SECURITY,
    ERROR_HANDLING,
    CONCURRENCY,
    PERFORMANCE,
    API_CONTRACT,
    TEST_COVERAGE,
    MAINTAINABILITY,
    REQUIREMENT_GAP;

    /**
     * 模型给的字符串所对应的类别，词表外或缺席时为 {@code null}。
     *
     * <p>归一化沿用 {@link FindingKeys#normalizeCategory}，因此
     * {@code "Correctness "} 与 {@code "CORRECTNESS"} 既得到同一个
     * {@code finding_key}，也落成同一个值。
     */
    static FindingCategory of(String reported) {
        String normalized = FindingKeys.normalizeCategory(reported);
        if (normalized.isEmpty()) {
            return null;
        }
        for (FindingCategory category : values()) {
            if (category.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
