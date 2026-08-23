package com.forgepilot.review;

/**
 * 一个 Finding 报告的是哪一类问题。
 *
 * <p>这个区分对 {@code finding_key} 是承重的：{@link #CODE_QUALITY} 的 key 是
 * 路径 + 归一化位置 + 类别，而 {@link #REQUIREMENT} 的 key 还必须带上
 * {@code requirement_id} 与 {@code ac_key}（ARCHITECTURE.md 3.6）。
 * 它在 schema 层面同样被强制——CODE_QUALITY 的 finding 不得引用验收条件。
 */
public enum FindingType {

    CODE_QUALITY,
    REQUIREMENT
}
