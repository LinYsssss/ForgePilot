package com.forgepilot.review;

import java.util.List;

/**
 * 一次 Review 经过校验之后的结果：每条验收条件的裁定、通过校验存活下来的
 * finding，以及校验拒绝掉了什么。
 *
 * <p>这是 {@link ReviewOutputValidator} 产出的东西，绝不是模型直接返回的东西。
 * 这里的每个字段都已对照该 Review 自己的不可变上下文校验过——
 * 它那次需求修订的验收条件、召回白名单，以及实际展示给它的变更文件。
 *
 * <p>{@link #warnings} 之所以存在，是因为静默丢弃一条幻觉引用，
 * 只会让运维拿到一份更短的报告，却没有任何办法知道它被缩短过。
 * 这与对「未被审查的文件」立下的是同一条规则，
 * 只不过应用到了「无法核实的断言」上。
 */
public record ReviewOutput(List<AcResult> acVerdicts, List<FindingCandidate> findings,
        List<String> warnings) {

    public ReviewOutput {
        acVerdicts = List.copyOf(acVerdicts);
        findings = List.copyOf(findings);
        warnings = List.copyOf(warnings);
    }

    /**
     * 单条 AC 的裁定。{@code acKey} 与 {@code acId} 一并携带，因为它才是
     * 跨修订稳定的身份：发布新修订时 id 会变，key 不会，
     * 而历史页面需要跨修订做比对。
     */
    public record AcResult(long acId, String acKey, AcVerdict verdict) {
    }

    /**
     * 一条已通过校验、但尚未被赋予血缘的 finding——{@code continuity} 与
     * {@code carried_from_finding_id} 由 {@link FindingContinuityCalculator}
     * 事后判定，因为它们取决于该 PR 的历史，而不是这一次的回答。
     *
     * <p>{@code requirementId} 与 {@code requirementRevisionId} 是从 Review
     * 自己的上下文里复制来的，绝不从模型读取，因此一条 finding 不可能与
     * 它的父行相矛盾。模型能选的只有 {@code acId}，而那个值会对照
     * 当前修订的验收条件做检查。
     *
     * <p>{@code explanation}、{@code suggestion} 与 {@code confidence} 是模型
     * 自己的话和自己的把握（V9）；{@code evidence} 仍然只能是逐字引用。
     * 这条分界不是风格问题：那三个血缘 key 一个都不得覆盖模型的措辞，
     * 因此散文可以随措辞自由变动，代价是它们必须进不了 {@link #findingKey()}、
     * {@link #evidenceHash()} 与 {@link #basisHash()} 中的任何一个。
     * {@code category} 是唯一的例外，而它是封闭词表而不是散文——它一直都是
     * {@code finding_key} 的输入，V9 只是不再把它算完 key 就丢掉。
     */
    public record FindingCandidate(FindingType findingType, String path, Integer line, String evidence,
            FindingCategory category, String explanation, String suggestion, FindingConfidence confidence,
            Long requirementId, Long requirementRevisionId, Long acId, String acKey,
            String findingKey, String evidenceHash, String basisHash) {
    }
}
