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
 * Splits a pull request's changed files into batches and runs the batch phase of
 * one Review (D002, ARCHITECTURE.md 3.4).
 *
 * <p>"One Review" is not "one LLM call", but it is still one pipeline. A small
 * pull request produces a single batch and travels the same path as a large one;
 * there is no shortcut branch, because a batch that produced verdicts for the
 * small case would be exactly the second pipeline 3.4 forbids. Batches produce
 * finding candidates and AC evidence only — the final synthesis concludes.
 *
 * <p>Two failure rules are absolute here. If any batch answer is still invalid
 * after its one format repair, the <strong>whole Review</strong> is FAILED and
 * this returns nothing to store: half a report reads like a short one. And every
 * file that was not reviewed is named in the coverage manifest, because a diff
 * that was silently cut produces a clean-looking review of code nobody read.
 */
@Component
public class ChangedFileBatcher {

    /**
     * Appended to a patch that had to be cut, so the model is told its input is
     * incomplete. Without it a truncated tail reads as absent code and invites a
     * finding about something the file actually does further down.
     */
    static final String TRUNCATION_MARKER = "\n[patch truncated]";

    private final ReviewOutputValidator validator;
    private final int maxChangedFiles;
    private final int maxPatchChars;
    private final int batchBudgetChars;

    /**
     * The three bounds are configuration, not constants in code. ARCHITECTURE.md
     * 7.2 freezes their defaults from the Phase 6 maximum-budget measurement on
     * the 4 GB target machine. Keeping them configurable makes an explicit
     * deployment override possible without creating a second batching path.
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
     * Decides what will be reviewed and in which batches, before anything is sent
     * anywhere. Files are taken in the canonical path-byte order the fingerprint
     * already uses, so the plan does not depend on the provider's paging.
     *
     * <p>A file goes to {@code notReviewed} when it is past the changed-file bound,
     * when the provider supplied no patch at all (binary, or past the provider's own
     * diff limit), or when not even one whole line of it fits a batch. An
     * over-long patch is cut at a line boundary and marked, which is 7.2's rule
     * and D002's spirit: cut, but never quietly.
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
     * Runs every batch and collects what they found.
     *
     * <p>Candidates are not de-duplicated here and do not need to be: a batch may
     * only report on the files it was shown, so the same path — and therefore the
     * same {@code finding_key} (3.4.3) — cannot come back from two batches. Dedup
     * within one answer is the validator's, next to the rest of its checks.
     *
     * <p>The first batch whose answer survives neither parsing nor its one repair
     * ends the phase as FAILED, discarding what earlier batches produced. That
     * discard is the point: a Review reporting three of five batches would look
     * complete and be wrong about the other two.
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

    /** Keeps whole lines only, and returns null when not even the first line fits. */
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

    /** What will be sent, and what will not. Both halves are stored: the manifest is part of the Review's snapshot. */
    public record Plan(List<Batch> batches, Coverage coverage) {

        public Plan {
            batches = List.copyOf(batches);
        }
    }

    /** One call's worth of files. {@code index} is 1-based and only ever used to name a batch in a failure reason. */
    public record Batch(int index, List<ChangedFile> files) {

        public Batch {
            files = List.copyOf(files);
        }
    }

    /**
     * The coverage manifest. {@code notReviewed} is a list that may be empty and
     * is never omitted: the API contract requires "empty" and "absent" to stay
     * distinguishable, because only one of them means "everything was read".
     */
    public record Coverage(boolean truncated, List<FileCoverage> files, List<String> notReviewed) {

        public Coverage {
            files = List.copyOf(files);
            notReviewed = List.copyOf(notReviewed);
        }
    }

    /** A reviewed file, and whether its patch had to be cut to fit (7.2's "标注"). */
    public record FileCoverage(String path, boolean patchTruncated) {
    }

    /**
     * The batch phase's result, in the vocabulary of the execution state machine.
     * The constructor enforces the rule that matters: a FAILED phase carries
     * nothing to store, so no caller can persist a partial report by accident.
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
     * How one batch reaches the model. {@link #repair} is the single format repair
     * 3.5 allows, and it is called at most once per batch — the budget belongs to
     * the batch, not to the parse loop.
     */
    public interface BatchReviewer {

        String review(Batch batch);

        String repair(Batch batch, String malformedAnswer);
    }
}
