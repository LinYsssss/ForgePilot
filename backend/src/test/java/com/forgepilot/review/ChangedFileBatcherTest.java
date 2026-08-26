package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.forgepilot.review.ChangedFileBatcher.Batch;
import com.forgepilot.review.ChangedFileBatcher.BatchPhase;
import com.forgepilot.review.ChangedFileBatcher.BatchReviewer;
import com.forgepilot.review.ChangedFileBatcher.FileCoverage;
import com.forgepilot.review.ChangedFileBatcher.Plan;
import com.forgepilot.review.ReviewOutput.FindingCandidate;
import com.forgepilot.review.ReviewOutputValidator.Context;
import com.forgepilot.scm.ChangedFile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The batch phase, with the bounds given explicitly rather than taken from
 * configuration — a test that ran against the deployed budget would change
 * meaning the moment a new measurement changes that budget.
 */
class ChangedFileBatcherTest {

    private static final int MAX_FILES = 3;
    private static final int MAX_PATCH_CHARS = 80;
    private static final int BATCH_BUDGET = 100;

    private final ReviewOutputValidator validator = new ReviewOutputValidator(JsonMapper.builder().build());
    private final ChangedFileBatcher batcher =
            new ChangedFileBatcher(validator, MAX_FILES, MAX_PATCH_CHARS, BATCH_BUDGET);

    /** New-side lines 1..{@code addedLines}+1 exist. */
    private static ChangedFile file(String path, int addedLines) {
        StringBuilder patch = new StringBuilder("@@ -1,1 +1," + (addedLines + 1) + " @@\n line one\n");
        for (int line = 0; line < addedLines; line++) {
            patch.append("+added ").append(line).append('\n');
        }
        return new ChangedFile(path, "MODIFIED", patch.toString());
    }

    private static Context context() {
        return new Context(7L, 9L, "the requirement body",
                List.of(new Context.Ac(11L, "AC-1", "the first criterion")), Map.of(), List.of());
    }

    // -------------------------------------------------------------- the plan

    @Test
    void everyFileThatWillNotBeReviewedIsNamedInTheManifest() {
        Plan plan = batcher.plan(List.of(
                file("a.txt", 1),
                new ChangedFile("b.bin", "MODIFIED", null),
                file("c.txt", 1),
                file("d.txt", 1),
                file("e.txt", 1)));

        assertThat(plan.coverage().files()).extracting(FileCoverage::path).containsExactly("a.txt", "c.txt");
        assertThat(plan.coverage().notReviewed())
                .as("a file with no patch and every file past the bound must be visible, not silently gone")
                .containsExactly("b.bin", "d.txt", "e.txt");
        assertThat(plan.coverage().truncated()).isTrue();
        assertThat(plan.batches()).flatExtracting(Batch::files).extracting(ChangedFile::path)
                .containsExactly("a.txt", "c.txt");
    }

    @Test
    void aCompleteManifestSaysSoRatherThanSayingNothing() {
        Plan plan = batcher.plan(List.of(file("a.txt", 1), file("b.txt", 1)));

        assertThat(plan.coverage().truncated()).isFalse();
        assertThat(plan.coverage().notReviewed()).isEmpty();
        assertThat(plan.coverage().files()).allMatch(file -> !file.patchTruncated());
    }

    @Test
    void anOverlongPatchIsCutAtALineBoundaryAndTheCutIsAnnotated() {
        ChangedFile huge = file("a.txt", 40);
        assertThat(huge.patch().length()).isGreaterThan(MAX_PATCH_CHARS);

        Plan plan = batcher.plan(List.of(huge));
        String sent = plan.batches().getFirst().files().getFirst().patch();

        assertThat(plan.coverage().files()).containsExactly(new FileCoverage("a.txt", true));
        assertThat(plan.coverage().truncated()).isTrue();
        assertThat(sent).endsWith(ChangedFileBatcher.TRUNCATION_MARKER);
        assertThat(sent.length()).isLessThanOrEqualTo(MAX_PATCH_CHARS);
        assertThat(huge.patch()).startsWith(sent.substring(0, sent.length()
                - ChangedFileBatcher.TRUNCATION_MARKER.length()));
        assertThat(sent.lines().filter(row -> row.startsWith("+")).toList())
                .allMatch(row -> huge.patch().contains(row + "\n"));
    }

    @Test
    void aFileThatCannotFitAWholeBatchIsNotReviewedRatherThanQuietlyShortened() {
        ChangedFileBatcher generousPerFile = new ChangedFileBatcher(validator, MAX_FILES, 100_000, 80);

        Plan plan = generousPerFile.plan(List.of(file("a.txt", 20)));

        assertThat(plan.batches()).isEmpty();
        assertThat(plan.coverage().notReviewed()).containsExactly("a.txt");
        assertThat(plan.coverage().truncated()).isTrue();
    }

    @Test
    void filesAreSplitIntoBatchesThatFitTheBudget() {
        List<ChangedFile> files = List.of(file("c.txt", 3), file("a.txt", 3), file("b.txt", 3));

        Plan plan = batcher.plan(files);

        assertThat(plan.batches()).hasSizeGreaterThan(1);
        for (Batch batch : plan.batches()) {
            int cost = batch.files().stream()
                    .mapToInt(file -> file.path().length() + file.patch().length()).sum();
            assertThat(cost).isLessThanOrEqualTo(BATCH_BUDGET);
        }
        // Canonical path-byte order, so the plan does not depend on the provider's
        // paging — the same order the review input fingerprint already uses.
        assertThat(plan.batches()).flatExtracting(Batch::files).extracting(ChangedFile::path)
                .containsExactly("a.txt", "b.txt", "c.txt");
    }

    // -------------------------------------------------------------- the phase

    @Test
    void oneBatchThatSurvivesNeitherParsingNorItsRepairFailsTheWholeReview() {
        Plan plan = batcher.plan(List.of(file("a.txt", 3), file("b.txt", 3)));
        assertThat(plan.batches()).hasSize(2);
        ScriptedReviewer reviewer = new ScriptedReviewer()
                .answers(1, batchAnswer(finding("a.txt", 2), ""))
                .answers(2, "{ not json")
                .repairs(2, "{ still not json");

        BatchPhase phase = batcher.run(plan, context(), reviewer);

        assertThat(phase.status())
                .as("the verdict must be FAILED, not an exception the caller may choose to ignore")
                .isEqualTo(ReviewStatus.FAILED);
        assertThat(phase.candidates())
                .as("batch 1 succeeded and its finding must still not reach the caller")
                .isEmpty();
        assertThat(phase.evidence()).isEmpty();
        assertThat(phase.failureReason()).contains("batch 2");
        assertThat(reviewer.repaired).containsExactly(2);
    }

    @Test
    void aFailedPhaseCannotBeGivenAPartialResultAtAll() {
        Plan plan = batcher.plan(List.of(file("a.txt", 3)));
        BatchPhase good = batcher.run(plan, context(),
                new ScriptedReviewer().answers(1, batchAnswer(finding("a.txt", 2), "")));
        assertThat(good.candidates()).hasSize(1);

        assertThatThrownBy(() -> new BatchPhase(ReviewStatus.FAILED, good.candidates(), List.of(),
                List.of(), "a reason")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchPhase(ReviewStatus.FAILED, List.of(), List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachBatchGetsExactlyOneRepairAndAGoodRepairIsAccepted() {
        Plan plan = batcher.plan(List.of(file("a.txt", 3), file("b.txt", 3)));
        ScriptedReviewer reviewer = new ScriptedReviewer()
                .answers(1, "{ not json")
                .repairs(1, batchAnswer(finding("a.txt", 2), ""))
                .answers(2, batchAnswer(finding("b.txt", 2), ""));

        BatchPhase phase = batcher.run(plan, context(), reviewer);

        assertThat(phase.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(phase.candidates()).extracting(FindingCandidate::path).containsExactly("a.txt", "b.txt");
        assertThat(reviewer.repaired).containsExactly(1);
    }

    @Test
    void aBatchCannotReportOnAFileItWasNotShown() {
        Plan plan = batcher.plan(List.of(file("a.txt", 3), file("b.txt", 3)));
        BatchPhase phase = batcher.run(plan, context(), new ScriptedReviewer()
                .answers(1, batchAnswer(finding("b.txt", 2), ""))
                .answers(2, batchAnswer(finding("b.txt", 2), "")));

        // This is also what makes "not reviewed" true rather than aspirational: no
        // finding can be attributed to a file the manifest says nobody read.
        assertThat(phase.candidates()).hasSize(1);
        assertThat(phase.warnings()).anyMatch(warning -> warning.contains("was shown"));
    }

    @Test
    void aBatchProducesEvidenceAndNeverAVerdict() {
        Plan plan = batcher.plan(List.of(file("a.txt", 3)));
        String claimsAVerdict = "{\"findings\":[],\"acVerdicts\":[{\"acId\":11,\"verdict\":\"COVERED\"}],"
                + "\"acEvidence\":[{\"acId\":11,\"path\":\"a.txt\",\"line\":2,\"excerpt\":\"+added 0\"}]}";

        BatchPhase phase = batcher.run(plan, context(), new ScriptedReviewer().answers(1, claimsAVerdict));

        assertThat(phase.evidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.acKey()).isEqualTo("AC-1"));
        // Only the final synthesis concludes. The batch said COVERED and the
        // Review still says NOT_FOUND, because the batch's claim never left the batch.
        assertThat(validator.validate("{\"acVerdicts\":[],\"findings\":[]}",
                        answer -> answer, context()).output().acVerdicts())
                .containsExactly(new ReviewOutput.AcResult(11L, "AC-1", AcVerdict.NOT_FOUND));
    }

    private static String batchAnswer(String findings, String evidence) {
        return "{\"findings\":[" + findings + "],\"acEvidence\":[" + evidence + "]}";
    }

    private static String finding(String path, int line) {
        return "{\"type\":\"CODE_QUALITY\",\"path\":\"" + path + "\",\"line\":" + line
                + ",\"category\":\"style\",\"evidence\":\"+added 0\"}";
    }

    /** Answers by batch index, so a test can make exactly one batch misbehave. */
    private static final class ScriptedReviewer implements BatchReviewer {

        private final Map<Integer, String> answers = new java.util.HashMap<>();
        private final Map<Integer, String> repairs = new java.util.HashMap<>();
        private final List<Integer> repaired = new ArrayList<>();

        private ScriptedReviewer answers(int batchIndex, String answer) {
            answers.put(batchIndex, answer);
            return this;
        }

        private ScriptedReviewer repairs(int batchIndex, String answer) {
            repairs.put(batchIndex, answer);
            return this;
        }

        @Override
        public String review(Batch batch) {
            return answers.get(batch.index());
        }

        @Override
        public String repair(Batch batch, String malformedAnswer) {
            repaired.add(batch.index());
            return repairs.getOrDefault(batch.index(), "");
        }
    }
}
