package com.forgepilot.review;

import java.util.Locale;

/**
 * 模型对某条 Finding 有多大把握（D021）。
 *
 * <p>它是**分档而非数字**，且这不是省事：模型自报的把握没有经过校准，
 * 一个 {@code 0.87} 会让读者以为它是校准过的概率，而它并不是。三个档位
 * 说得清「更值得先看」，又说不出它并不知道的精度。
 *
 * <p>三条硬约束：它**绝不**进入 {@code finding_key}、{@code evidence_hash} 或
 * {@code basis_hash} 中的任何一个——否则模型换一次措辞就会让同一个问题变成
 * 「新问题」，跨轮次抑制与血缘同时失效；它**绝不**参与任何自动门禁或状态流转
 * （PRD.md 5 的人工生命周期不因它而变，
 * {@code LEGACY-MIGRATION-MATRIX.md} 把按置信度自动 gate 的
 * {@code FindingDecisionEntity} 标为 DROP）；它在 UI 上与 Finding 人工状态、
 * Review Decision 三者分开呈现，不合并成一个综合徽章（PRD.md 6、ARCHITECTURE.md）。
 */
public enum FindingConfidence {

    HIGH,
    MEDIUM,
    LOW;

    /** 模型给的字符串所对应的档位，词表外或缺席时为 {@code null}（不落库）。 */
    static FindingConfidence of(String reported) {
        if (reported == null) {
            return null;
        }
        String normalized = reported.strip().toUpperCase(Locale.ROOT);
        for (FindingConfidence confidence : values()) {
            if (confidence.name().equals(normalized)) {
                return confidence;
            }
        }
        return null;
    }
}
