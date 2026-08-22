package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import com.forgepilot.review.ReviewOutput.AcResult;
import com.forgepilot.review.ReviewOutput.FindingCandidate;
import com.forgepilot.review.ReviewOutputValidator.Context;
import com.forgepilot.review.ReviewOutputValidator.Outcome;
import com.forgepilot.scm.ChangedFile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validation is a pure function of one answer and one Review context, so this
 * needs no database and no provider.
 *
 * <p>The hash tests are the ones that matter most: they are the only evidence
 * that the suppression mechanism keys on source and requirements rather than on
 * the model's wording (PRD R4). They are written against the whole validator
 * rather than against {@code FindingKeys} directly, so that hashing the raw
 * answer — the obvious way to break R4 — would fail them.
 */
class ReviewOutputValidatorTest {

    private static final String FILE = "src/main/java/A.java";

    /** New-side lines 1..4 exist; anything else in this file is unverifiable. */
    private static final String PATCH = """
            @@ -1,3 +1,5 @@
             package a;
            +
            +class A {}
             // tail
            """;

    private static final ChangedFile CHANGED = new ChangedFile(FILE, "MODIFIED", PATCH);
    private static final UnaryOperator<String> NO_REPAIR = answer -> {
        throw new AssertionError("a valid answer must not be repaired");
    };

    private final ReviewOutputValidator validator = new ReviewOutputValidator(JsonMapper.builder().build());

    private static Context context() {
        return context("the requirement body", "the first criterion");
    }

    private static Context context(String requirementText, String firstCriterionText) {
        return new Context(7L, 9L, requirementText,
                List.of(new Context.Ac(11L, "AC-1", firstCriterionText),
                        new Context.Ac(12L, "AC-2", "the second criterion")),
                Map.of(5L, "excerpt-hash-5", 6L, "excerpt-hash-6"),
                List.of(CHANGED));
    }

    private static String answer(String verdicts, String findings) {
        return "{\"acVerdicts\":[" + verdicts + "],\"findings\":[" + findings + "]}";
    }

    private ReviewOutput valid(String verdicts, String findings) {
        Outcome outcome = validator.validate(answer(verdicts, findings), NO_REPAIR, context());
        assertThat(outcome.status()).isEqualTo(ReviewStatus.COMPLETED);
        return outcome.output();
    }

    // ------------------------------------------------------------- AC verdicts

    @Test
    void anAcceptanceCriterionTheModelSkippedComesBackAsNotFound() {
        ReviewOutput output = valid("{\"acId\":11,\"verdict\":\"COVERED\"}", "");

        assertThat(output.acVerdicts()).containsExactly(
                new AcResult(11L, "AC-1", AcVerdict.COVERED),
                new AcResult(12L, "AC-2", AcVerdict.NOT_FOUND));
        assertThat(output.warnings())
                .as("a silent model and a negative model must stay distinguishable")
                .anyMatch(warning -> warning.contains("AC-2") && warning.contains("NOT_FOUND"));
    }

    @Test
    void aVerdictForACriterionOfAnotherRevisionIsRefused() {
        ReviewOutput output = valid(
                "{\"acId\":11,\"verdict\":\"COVERED\"},{\"acId\":999,\"verdict\":\"COVERED\"}", "");

        assertThat(output.acVerdicts()).extracting(AcResult::acId).containsExactly(11L, 12L);
        assertThat(output.acVerdicts()).noneMatch(result -> result.acId() == 999L);
        assertThat(output.warnings()).anyMatch(warning -> warning.contains("999"));
    }

    @Test
    void aFindingCitingACriterionOfAnotherRevisionIsRefused() {
        ReviewOutput output = valid("", finding("REQUIREMENT", FILE, 3, "class A {}", "\"acId\":999,"));

        assertThat(output.findings()).isEmpty();
        assertThat(output.warnings()).anyMatch(warning -> warning.contains("999"));
    }

    // ---------------------------------------------------------------- findings

    @Test
    void aFindingAboutAFileOutsideTheChangedFilesIsRefused() {
        ReviewOutput output = valid("", finding("CODE_QUALITY", "src/main/java/NotInThisPr.java", 3,
                "class A {}", ""));

        assertThat(output.findings()).isEmpty();
        assertThat(output.warnings()).anyMatch(warning -> warning.contains("NotInThisPr.java"));
    }

    @Test
    void aFindingCitingAKnowledgeSourceOutsideTheRecallWhitelistIsRefused() {
        ReviewOutput output = valid("", finding("CODE_QUALITY", FILE, 3, "class A {}", "\"sourceIds\":[5,404],"));

        assertThat(output.findings()).isEmpty();
        assertThat(output.warnings()).anyMatch(warning -> warning.contains("404"));
    }

    @Test
    void anUnverifiableLineIsDroppedAndTheFindingSurvivesWithoutIt() {
        ReviewOutput output = valid("", finding("CODE_QUALITY", FILE, 99, "class A {}", ""));

        assertThat(output.findings()).hasSize(1);
        assertThat(output.findings().getFirst().line())
                .as("3.5 forbids emitting a line the patch cannot confirm")
                .isNull();
    }

    @Test
    void aLineTheHunkCoversIsKeptExactly() {
        assertThat(valid("", finding("CODE_QUALITY", FILE, 3, "class A {}", ""))
                .findings().getFirst().line()).isEqualTo(3);
        assertThat(valid("", finding("CODE_QUALITY", FILE, 4, "class A {}", ""))
                .findings().getFirst().line()).isEqualTo(4);
        assertThat(valid("", finding("CODE_QUALITY", FILE, 5, "class A {}", ""))
                .findings().getFirst().line()).isNull();
    }

    @Test
    void aFindingsRequirementContextIsCopiedFromTheReviewNotFromTheModel() {
        FindingCandidate candidate = valid("", finding("REQUIREMENT", FILE, 3, "class A {}", "\"acId\":11,"))
                .findings().getFirst();

        // The constraint trigger compares these two against the parent Review; a
        // value the model chose could only ever disagree with it.
        assertThat(candidate.requirementId()).isEqualTo(7L);
        assertThat(candidate.requirementRevisionId()).isEqualTo(9L);
        assertThat(candidate.acId()).isEqualTo(11L);
        assertThat(candidate.acKey()).isEqualTo("AC-1");
    }

    // ------------------------------------------------------- malformed answers

    @Test
    void anAnswerThatIsStillInvalidAfterOneRepairFailsTheWholeReview() {
        AtomicInteger repairs = new AtomicInteger();

        Outcome outcome = validator.validate("this is not JSON at all",
                answer -> {
                    repairs.incrementAndGet();
                    return "{\"acVerdicts\": oops";
                }, context());

        assertThat(outcome.status())
                .as("the verdict itself must be FAILED, not merely an absence of output")
                .isEqualTo(ReviewStatus.FAILED);
        assertThat(outcome.output()).isNull();
        assertThat(outcome.failureReason()).isNotBlank();
        assertThat(repairs).hasValue(1);
    }

    @Test
    void theOneRepairIsRealAndItsResultIsValidatedLikeAnyOtherAnswer() {
        Outcome outcome = validator.validate("{",
                answer -> answer("{\"acId\":11,\"verdict\":\"AT_RISK\"}", ""), context());

        assertThat(outcome.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(outcome.output().acVerdicts()).contains(new AcResult(11L, "AC-1", AcVerdict.AT_RISK));
    }

    @Test
    void anAnswerMissingAnArrayIsAStructuralFailureRatherThanAnEmptyReport() {
        Outcome outcome = validator.validate("{\"acVerdicts\":[]}", answer -> "{\"acVerdicts\":[]}", context());

        assertThat(outcome.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(outcome.output()).isNull();
    }

    @Test
    void aFailedOutcomeCannotBeGivenAnOutputAtAll() {
        ReviewOutput report = new ReviewOutput(List.of(), List.of(), List.of());

        assertThatThrownBy(() -> new Outcome(ReviewStatus.FAILED, report, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Outcome(ReviewStatus.COMPLETED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- the hashes

    @Test
    void rewordingTheModelsProseChangesNoKeyAndNoHash() {
        String terse = "{\"type\":\"REQUIREMENT\",\"acId\":11,\"path\":\"" + FILE + "\",\"line\":3,"
                + "\"category\":\"missing-check\",\"evidence\":\"class A {}\","
                + "\"title\":\"Missing check\",\"severity\":\"HIGH\","
                + "\"description\":\"The class does not check its input.\"}";
        String verbose = "{ \"description\" : \"This class, regrettably, omits the input check entirely.\",\n"
                + "  \"severity\":\"CRITICAL\", \"title\":\"No validation whatsoever\",\n"
                + "  \"evidence\":\"class A {}\", \"category\":\"Missing-Check \",\n"
                + "  \"line\":3, \"path\":\"" + FILE + "\", \"acId\":11, \"type\":\"REQUIREMENT\" }";

        FindingCandidate first = valid("", terse).findings().getFirst();
        FindingCandidate second = valid("", verbose).findings().getFirst();

        assertThat(second.findingKey()).isEqualTo(first.findingKey());
        assertThat(second.evidenceHash()).isEqualTo(first.evidenceHash());
        assertThat(second.basisHash()).isEqualTo(first.basisHash());
    }

    @Test
    void oneChangedByteOfSourceEvidenceChangesTheEvidenceHash() {
        FindingCandidate before = valid("", finding("CODE_QUALITY", FILE, 3, "class A {}", "")).findings().getFirst();
        FindingCandidate after = valid("", finding("CODE_QUALITY", FILE, 3, "class B {}", "")).findings().getFirst();

        assertThat(after.evidenceHash()).isNotEqualTo(before.evidenceHash());
        assertThat(after.findingKey())
                .as("the key locates the finding; the evidence hash decides whether it may be inherited")
                .isEqualTo(before.findingKey());
    }

    @Test
    void changingTheCitedCriterionChangesOnlyTheBasisHash() {
        String reported = finding("REQUIREMENT", FILE, 3, "class A {}", "\"acId\":11,");
        String body = answer("", reported);

        FindingCandidate before = validator.validate(body, NO_REPAIR, context()).output().findings().getFirst();
        FindingCandidate after = validator.validate(body, NO_REPAIR,
                context("the requirement body", "the first criterion, now with a bound"))
                .output().findings().getFirst();

        assertThat(after.basisHash()).isNotEqualTo(before.basisHash());
        assertThat(after.evidenceHash()).isEqualTo(before.evidenceHash());
        assertThat(after.findingKey()).isEqualTo(before.findingKey());
    }

    @Test
    void changingACitedKnowledgeExcerptChangesTheBasisHash() {
        String body = answer("", finding("CODE_QUALITY", FILE, 3, "class A {}", "\"sourceIds\":[5],"));
        Context reIngested = new Context(7L, 9L, "the requirement body",
                List.of(new Context.Ac(11L, "AC-1", "the first criterion"),
                        new Context.Ac(12L, "AC-2", "the second criterion")),
                Map.of(5L, "excerpt-hash-5-rewritten", 6L, "excerpt-hash-6"), List.of(CHANGED));

        assertThat(validator.validate(body, NO_REPAIR, reIngested).output().findings().getFirst().basisHash())
                .isNotEqualTo(validator.validate(body, NO_REPAIR, context()).output().findings().getFirst().basisHash());
    }

    @Test
    void indentationIsNeverFoldedButLineEndingsAndHunkNumbersAre() {
        // YAML: two spaces and four spaces are two different documents.
        assertThat(FindingKeys.evidenceHash("root:\n  child: 1"))
                .isNotEqualTo(FindingKeys.evidenceHash("root:\n    child: 1"));
        // Python: the dedented line leaves the block.
        assertThat(FindingKeys.evidenceHash("if x:\n    return 1"))
                .isNotEqualTo(FindingKeys.evidenceHash("if x:\nreturn 1"));
        // A trailing space is content too, not decoration.
        assertThat(FindingKeys.evidenceHash("value: |\n  keep \n"))
                .isNotEqualTo(FindingKeys.evidenceHash("value: |\n  keep\n"));

        // The two normalizations that are required, proved to actually happen.
        assertThat(FindingKeys.evidenceHash("root:\r\n  child: 1"))
                .isEqualTo(FindingKeys.evidenceHash("root:\n  child: 1"));
        assertThat(FindingKeys.evidenceHash("@@ -1,3 +1,5 @@ class A\n context"))
                .isEqualTo(FindingKeys.evidenceHash("@@ -90,3 +140,5 @@ class A\n context"));
    }

    @Test
    void twoFilesDifferingOnlyInCaseAreTwoDifferentFindings() {
        ChangedFile upper = new ChangedFile("src/main/java/Api.java", "MODIFIED", PATCH);
        ChangedFile lower = new ChangedFile("src/main/java/api.java", "MODIFIED", PATCH);
        Context both = new Context(null, null, null, List.of(), Map.of(), List.of(upper, lower));

        List<FindingCandidate> findings = validator.validate(answer("",
                finding("CODE_QUALITY", upper.path(), 3, "class A {}", "") + ","
                        + finding("CODE_QUALITY", lower.path(), 3, "class A {}", "")),
                NO_REPAIR, both).output().findings();

        assertThat(findings).hasSize(2);
        assertThat(findings.getFirst().findingKey()).isNotEqualTo(findings.get(1).findingKey());
    }

    @Test
    void theSameFindingReportedTwiceIsStoredOnce() {
        String once = finding("CODE_QUALITY", FILE, 3, "class A {}", "");
        ReviewOutput output = valid("", once + "," + once);

        // uq_finding_review_key would refuse the second row and abort the whole
        // insert with it, so the duplicate has to go before the write.
        assertThat(output.findings()).hasSize(1);
        assertThat(output.warnings()).anyMatch(warning -> warning.contains("duplicate"));
    }

    private static String finding(String type, String path, int line, String evidence, String extraFields) {
        return "{\"type\":\"" + type + "\"," + extraFields + "\"path\":\"" + path + "\",\"line\":" + line
                + ",\"category\":\"missing-check\",\"evidence\":\"" + evidence + "\"}";
    }
}
