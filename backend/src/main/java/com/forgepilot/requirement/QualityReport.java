package com.forgepilot.requirement;

import java.time.Instant;
import java.util.List;

/**
 * 对某一次修订做一次需求质量检查的结果（API.md）：
 * 先跑确定性规则，再做一次结构化 AI 评估。
 *
 * <p>结果中点名了具体修订，因为这个结果就属于那次修订。质量结果是
 * <em>建议</em>：这里没有任何东西会改动 {@code requirement.status}，
 * 也刻意没有总评或评分字段——因为一个数字恰恰会被当成闸门来读
 * （PRD 5，“质量检查是建议，不是工作流状态”）。
 *
 * <p>{@code qualityVersion} 与 {@code checkedAt} 是让存下来的结果日后仍可解读的
 * 两个持久化列：一份报告只有对着产生它的那套规则与 Prompt、以及运行当时存在的
 * 那份文本，才有意义。
 */
public record QualityReport(long requirementId, long revisionId, int revisionSeq, String qualityVersion,
        Instant checkedAt, List<RuleFinding> rules, AiAssessment ai) {

    /**
     * 确定性的那一半。每个取值都指向一种无需模型即可判定的缺陷，
     * 且每一种都能真的通过 API 发生——那些 {@code RequirementContent} 的
     * bean validation 已经拒绝的状态（空标题、无正文的条件、一条验收条件都没有
     * 的修订）永远不会触发，因此不在此列。
     */
    public enum Rule {

        /**
         * 背景与描述都不存在。两者都是可选列，因此这种情况确实可达；
         * 它意味着 {@code ReviewContext.requirement}（ARCHITECTURE.md 4.2）
         * 除了一个最长 200 字符的标题之外什么都没带——审查在找“需求违规”
         * （PRD 2）时，几乎没有任何东西可以拿来与 diff 对照。
         */
        MISSING_DESCRIPTION,

        /**
         * 本次修订中有两条验收条件文本完全相同。每条 AC 都会得到自己的裁定
         * （API.md）和自己的 {@code finding_key}，而 REQUIREMENT 类
         * Finding 的 key 是 {@code requirement_id + ac_key}（ARCHITECTURE.md 3.4）。
         * 于是同一个缺陷会以两个 key 被报告两次；又因为抑制是按
         * {@code finding_key} 做的，驳回其中一份并不会在下次审查时抑制另一份
         * ——而且是永久如此。
         */
        DUPLICATE_CRITERION,

        /**
         * 这条需求产生的 Prompt 超过了网关的字符预算（ARCHITECTURE.md 7.2），
         * 于是 {@code PromptSanitizer} 会在任何模型看到它之前把尾巴切掉。
         * 把这件事报出来正是要点所在：被截断却仍然“成功作答”，
         * 就是那种绝不允许的静默截断。
         */
        PROMPT_BUDGET_EXCEEDED
    }

    /** 命中的一条规则。只有针对单条验收条件的规则才会设置 {@code acKey}。 */
    public record RuleFinding(Rule rule, String acKey, String message) {
    }

    /** 唯一那次结构化 AI 回答。{@code issues} 为空列表表示模型没发现问题。 */
    public record AiAssessment(String summary, List<AiIssue> issues) {
    }

    /** 模型报告的一个问题，可选地挂在某一条验收条件上。 */
    public record AiIssue(String acKey, String message) {
    }

    /**
     * {@code requirement_revision.quality_json} 里存的正是这个结构。版本号与
     * 时间戳在同一行上各有自己的列，因此在文档内部再重复一遍只会制造
     * 两个可能互相矛盾的地方。
     */
    record Stored(List<RuleFinding> rules, AiAssessment ai) {
    }
}
