package com.forgepilot.review;

import java.util.ArrayList;
import java.util.List;

import com.forgepilot.review.ReviewOutput.FindingCandidate;
import com.forgepilot.review.ReviewOutputValidator.AcEvidence;
import com.forgepilot.review.ReviewOutputValidator.BatchAnswer;
import com.forgepilot.scm.ChangedFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把一个 PR 的变更文件切成若干批次，并运行一次 Review 的分批阶段
 * （ARCHITECTURE.md 3.4）。
 *
 * <p>“一次 Review”不等于“一次 LLM 调用”，但它仍然是**一条**流水线。
 * 小 PR 只产生一个批次，走的是与大 PR 完全相同的路径；这里没有捷径分支，
 * 因为一个会为小 PR 直接给出裁定的批次，恰恰就是 3.4 明令禁止的第二条流水线。
 * 批次只产出 finding 候选与 AC 证据——下结论的是最终综合阶段。
 *
 * <p>这里有两条绝对的失败规则。若任何一个批次的回答在用掉它那一次格式修复之后
 * 仍然非法，则<strong>整次 Review</strong> 判为 FAILED，本方法不返回任何可供
 * 存储的东西：半份报告读起来就像一份短报告。以及，每一个未被审查的文件都会
 * 出现在覆盖清单里，因为被静默裁掉的 diff 会产出一份「看上去很干净」的
 * 审查结果——而那部分代码根本没人读过。
 */
@Component
public class ChangedFileBatcher {

    /**
     * 追加在被迫裁剪过的 patch 之后，好让模型知道它拿到的输入并不完整。
     * 没有它，被截断的尾部读起来就像「这段代码不存在」，
     * 从而诱导模型对该文件后面其实已经做过的事情报告一条 finding。
     */
    static final String TRUNCATION_MARKER = "\n[patch truncated]";

    private final ReviewOutputValidator validator;
    private final int maxChangedFiles;
    private final int maxPatchChars;
    private final int batchBudgetChars;

    /**
     * 这三个上界是配置项，而不是写死在代码里的常量。ARCHITECTURE.md 7.2
     * 依据 4 GB 目标机上的最大预算实测冻结了它们的默认值。
     * 保持可配置，使得部署方可以显式覆盖它们，
     * 而不必因此另开一条分批路径。
     */
    ChangedFileBatcher(ReviewOutputValidator validator,
            @Value("${forgepilot.review.max-changed-files:300}") int maxChangedFiles,
            @Value("${forgepilot.review.max-patch-chars:60000}") int maxPatchChars,
            @Value("${forgepilot.review.batch-budget-chars:60000}") int batchBudgetChars) {
        this.validator = validator;
        this.maxChangedFiles = maxChangedFiles;
        this.maxPatchChars = maxPatchChars;
        this.batchBudgetChars = batchBudgetChars;
    }

    /**
     * 在任何内容被发出去之前，先决定「要审查什么」以及「分在哪些批次里」。
     * 文件按指纹已经采用的那套规范路径字节序取用，
     * 因此这份计划不依赖 provider 的分页方式。
     *
     * <p>一个文件会被放进 {@code notReviewed}，当它超出变更文件数上界、
     * 当 provider 压根没提供 patch（二进制文件，或超出 provider 自身的 diff 上限），
     * 或者当它连完整的一行都塞不进一个批次时。过长的 patch 会在行边界处裁剪
     * 并加以标注——这既是 7.2 的规则，也是分批契约的精神：可以裁，
     * 但绝不能悄悄地裁。
     */
    public Plan plan(List<ChangedFile> changedFiles) {
        List<FileCoverage> reviewed = new ArrayList<>();
        List<String> notReviewed = new ArrayList<>();
        List<Batch> batches = new ArrayList<>();
        List<ChangedFile> current = new ArrayList<>();
        int currentChars = 0;
        boolean cutAnyPatch = false;

        List<ChangedFile> ordered = ChangedFile.canonicalOrder(changedFiles);
        for (int index = 0; index < ordered.size(); index++) {
            ChangedFile file = ordered.get(index);
            if (index >= maxChangedFiles || file.patch() == null) {
                notReviewed.add(file.path());
                continue;
            }
            boolean cut = file.patch().length() > maxPatchChars;
            String patch = cut ? cutAtLineBoundary(file.patch()) : file.patch();
            if (patch == null || file.path().length() + patch.length() > batchBudgetChars) {
                notReviewed.add(file.path());
                continue;
            }
            int cost = file.path().length() + patch.length();
            if (!current.isEmpty() && currentChars + cost > batchBudgetChars) {
                batches.add(new Batch(batches.size() + 1, List.copyOf(current)));
                current.clear();
                currentChars = 0;
            }
            current.add(new ChangedFile(file.path(), file.changeType(), patch));
            currentChars += cost;
            reviewed.add(new FileCoverage(file.path(), cut));
            cutAnyPatch |= cut;
        }
        if (!current.isEmpty()) {
            batches.add(new Batch(batches.size() + 1, List.copyOf(current)));
        }
        return new Plan(batches, new Coverage(cutAnyPatch || !notReviewed.isEmpty(), reviewed, notReviewed));
    }

    /**
     * 运行每一个批次并汇总它们的发现。
     *
     * <p>这里不对候选项去重，也不需要：一个批次只能对展示给它的文件作出报告，
     * 因此同一个路径——也就是同一个 {@code finding_key}（3.4.3）——
     * 不可能从两个批次里同时回来。单次回答内部的去重归校验器所有，
     * 与它的其余检查放在一起。
     *
     * <p>第一个「解析不过、那一次修复也救不回来」的批次会让整个阶段以 FAILED 收场，
     * 并丢弃此前各批次已经产出的东西。这次丢弃正是要点所在：
     * 一个只报告了五个批次中三个的 Review 看上去是完整的，
     * 而它对另外两个批次的判断是错的。
     */
    public BatchPhase run(Plan plan, ReviewOutputValidator.Context context, BatchReviewer reviewer) {
        List<FindingCandidate> candidates = new ArrayList<>();
        List<AcEvidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Batch batch : plan.batches()) {
            ReviewOutputValidator.Context visible = context.withVisibleFiles(batch.files());
            String answer = reviewer.review(batch);
            BatchAnswer parsed;
            try {
                parsed = validator.readBatch(answer, visible);
            } catch (ReviewOutputValidator.MalformedAnswer firstAttempt) {
                try {
                    parsed = validator.readBatch(reviewer.repair(batch, answer), visible);
                } catch (ReviewOutputValidator.MalformedAnswer afterRepair) {
                    return BatchPhase.failed("batch " + batch.index() + " of " + plan.batches().size()
                            + ": " + afterRepair.getMessage());
                }
            }
            candidates.addAll(parsed.candidates());
            evidence.addAll(parsed.evidence());
            warnings.addAll(parsed.warnings());
        }
        return BatchPhase.completed(candidates, evidence, warnings);
    }

    /** 只保留完整的行；若连第一行都塞不下则返回 null。 */
    private String cutAtLineBoundary(String patch) {
        int budget = maxPatchChars - TRUNCATION_MARKER.length();
        StringBuilder kept = new StringBuilder();
        for (String row : patch.split("\n", -1)) {
            if (kept.length() + row.length() + 1 > budget) {
                break;
            }
            kept.append(row).append('\n');
        }
        return kept.isEmpty() ? null : kept + TRUNCATION_MARKER;
    }

    /** 哪些会被发送、哪些不会。两半都会存下来：这份清单是该 Review 快照的一部分。 */
    public record Plan(List<Batch> batches, Coverage coverage) {

        public Plan {
            batches = List.copyOf(batches);
        }
    }

    /** 够一次调用用的那批文件。{@code index} 从 1 开始，且只用于在失败原因里点名某个批次。 */
    public record Batch(int index, List<ChangedFile> files) {

        public Batch {
            files = List.copyOf(files);
        }
    }

    /**
     * 覆盖清单。{@code notReviewed} 是一个**可以为空、但绝不省略**的列表：
     * API 契约要求「空」与「缺席」必须保持可区分，
     * 因为二者之中只有一个意味着「全部内容都被读过了」。
     */
    public record Coverage(boolean truncated, List<FileCoverage> files, List<String> notReviewed) {

        public Coverage {
            files = List.copyOf(files);
            notReviewed = List.copyOf(notReviewed);
        }
    }

    /** 一个被审查过的文件，以及它的 patch 是否为了塞下而被裁剪过（7.2 所说的“标注”）。 */
    public record FileCoverage(String path, boolean patchTruncated) {
    }

    /**
     * 分批阶段的结果，用执行状态机的词汇表达。构造器强制了那条真正要紧的规则：
     * FAILED 的阶段不携带任何可供存储的东西，
     * 因此没有任何调用方能够意外地把一份残缺报告持久化下来。
     */
    public record BatchPhase(ReviewStatus status, List<FindingCandidate> candidates,
            List<AcEvidence> evidence, List<String> warnings, String failureReason) {

        public BatchPhase {
            candidates = List.copyOf(candidates);
            evidence = List.copyOf(evidence);
            warnings = List.copyOf(warnings);
            if (status == ReviewStatus.COMPLETED) {
                if (failureReason != null) {
                    throw new IllegalArgumentException("A completed batch phase carries no failure reason.");
                }
            } else if (status == ReviewStatus.FAILED) {
                if (failureReason == null || !candidates.isEmpty() || !evidence.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A failed batch phase carries a reason and no partial result.");
                }
            } else {
                throw new IllegalArgumentException("A batch phase ends in COMPLETED or FAILED.");
            }
        }

        static BatchPhase completed(List<FindingCandidate> candidates, List<AcEvidence> evidence,
                List<String> warnings) {
            return new BatchPhase(ReviewStatus.COMPLETED, candidates, evidence, warnings, null);
        }

        static BatchPhase failed(String reason) {
            return new BatchPhase(ReviewStatus.FAILED, List.of(), List.of(), List.of(), reason);
        }
    }

    /**
     * 单个批次是如何抵达模型的。{@link #repair} 就是 3.5 允许的那唯一一次
     * 格式修复，每个批次最多调用一次——这笔预算属于**批次**，
     * 而不属于解析循环。
     */
    public interface BatchReviewer {

        String review(Batch batch);

        String repair(Batch batch, String malformedAnswer);
    }
}
