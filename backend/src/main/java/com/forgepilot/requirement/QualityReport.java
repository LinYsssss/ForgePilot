package com.forgepilot.requirement;

import java.time.Instant;
import java.util.List;

/**
 * The result of one Requirement Quality check on one revision (api-contract 4):
 * deterministic rules first, then a single structured AI assessment.
 *
 * <p>The revision is named in the answer because the result belongs to it (D011).
 * A quality result is <em>advice</em>: nothing here moves
 * {@code requirement.status}, and there is deliberately no overall verdict or
 * score field, because a number is exactly what would get read as a gate
 * (PRD 5, "质量检查是建议，不是工作流状态").
 *
 * <p>{@code qualityVersion} and {@code checkedAt} are the two persisted columns
 * that make a stored result interpretable later: a report is only meaningful
 * against the rule set and prompt that produced it, and against the prose that
 * existed when it ran.
 */
public record QualityReport(long requirementId, long revisionId, int revisionSeq, String qualityVersion,
        Instant checkedAt, List<RuleFinding> rules, AiAssessment ai) {

    /**
     * The deterministic half. Each value names a failure that is decidable
     * without a model, and each one can actually occur through the API — a rule
     * for a state {@code RequirementContent}'s bean validation already rejects
     * (a blank title, a criterion with no text, a revision with no criteria at
     * all) would never fire and is therefore not here.
     */
    public enum Rule {

        /**
         * Neither background nor description is present. Both are optional
         * columns, so this is reachable, and it means
         * {@code ReviewContext.requirement} (ARCHITECTURE.md 4.2) carries nothing
         * but a title of at most 200 characters — the review has almost nothing
         * to hold a diff against when looking for 需求违规 (PRD 2).
         */
        MISSING_DESCRIPTION,

        /**
         * Two criteria of this revision carry the same text. Every AC gets its own
         * verdict (api-contract 2.2) and its own {@code finding_key}, which for a
         * REQUIREMENT finding is {@code requirement_id + ac_key}
         * (ARCHITECTURE.md 3.4). So one defect is reported twice under two keys,
         * and because D009 suppresses per {@code finding_key}, rejecting one copy
         * does not suppress the other on the next review — permanently.
         */
        DUPLICATE_CRITERION,

        /**
         * The prompt this requirement produces is longer than the gateway's
         * character budget (ARCHITECTURE.md 7.2), so {@code PromptSanitizer} cuts
         * the tail off before any model sees it. Reporting that is the whole point:
         * a truncated input that still answers successfully is the silent
         * truncation D002 forbids.
         */
        PROMPT_BUDGET_EXCEEDED
    }

    /** One rule hit. {@code acKey} is set only by rules that are about one criterion. */
    public record RuleFinding(Rule rule, String acKey, String message) {
    }

    /** The single structured AI answer. An empty {@code issues} list means the model found nothing. */
    public record AiAssessment(String summary, List<AiIssue> issues) {
    }

    /** One problem the model reports, optionally against one acceptance criterion. */
    public record AiIssue(String acKey, String message) {
    }

    /**
     * Exactly what {@code requirement_revision.quality_json} carries. The version
     * and the timestamp are their own columns on the same row, so repeating them
     * inside the document would create two places to disagree.
     */
    record Stored(List<RuleFinding> rules, AiAssessment ai) {
    }
}
