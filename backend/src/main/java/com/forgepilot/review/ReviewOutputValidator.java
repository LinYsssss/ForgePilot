package com.forgepilot.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.forgepilot.review.ReviewOutput.AcResult;
import com.forgepilot.review.ReviewOutput.FindingCandidate;
import com.forgepilot.scm.ChangedFile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns one model answer into a {@link ReviewOutput} that is true about this
 * Review's own context, or into a FAILED verdict (ARCHITECTURE.md 3.5).
 *
 * <p>Two rules shape everything here:
 *
 * <ul>
 * <li>Every acceptance criterion of the reviewed revision ends with a verdict.
 * A criterion the model skipped is filled in as {@code NOT_FOUND}, so a silent
 * model can never shorten the report.</li>
 * <li>A structurally invalid answer gets <strong>one</strong> format repair.
 * If that fails the Review is FAILED — never an empty report that reads like a
 * clean pull request. {@link Outcome} makes that unrepresentable rather than
 * merely intended.</li>
 * </ul>
 *
 * <p>Individual claims are treated differently from structural failure: a
 * citation the model invented is dropped and named in {@link ReviewOutput#warnings()}
 * rather than failing the whole Review. That is also what keeps the batch insert
 * whole — a Finding whose context disagrees with its parent would abort the
 * entire batch at the constraint trigger, so validation happens before the write
 * and the trigger stays the last line of defence, not the first (design.md 4.4).
 */
@Component
public class ReviewOutputValidator {

    private final ObjectMapper json;

    ReviewOutputValidator(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Validates the final synthesis answer, allowing exactly one format repair.
     *
     * <p>{@code formatRepair} receives the answer that would not parse and returns
     * the model's second attempt. It is called at most once: 3.5 grants one repair,
     * and a loop here would quietly turn the AI gateway's own bounded retry into
     * four calls.
     */
    public Outcome validate(String answer, UnaryOperator<String> formatRepair, Context context) {
        try {
            return Outcome.completed(read(answer, context));
        } catch (MalformedAnswer firstAttempt) {
            try {
                return Outcome.completed(read(formatRepair.apply(answer), context));
            } catch (MalformedAnswer afterRepair) {
                return Outcome.failed(afterRepair.getMessage());
            }
        }
    }

    /**
     * Validates one batch answer. A batch reports candidates and evidence and
     * <strong>never</strong> a verdict (D002): it has seen part of the diff, so its
     * conclusion about an acceptance criterion would contradict another batch's.
     * Any {@code acVerdicts} field is therefore not even read.
     *
     * <p>Throws rather than returning an outcome because the repair budget belongs
     * to {@link ChangedFileBatcher}, which owns the whole batch phase and must fail
     * the entire Review — not just this batch — when the repair does not take.
     */
    BatchAnswer readBatch(String answer, Context context) {
        JsonNode root = parse(answer);
        List<String> warnings = new ArrayList<>();
        List<FindingCandidate> candidates = readFindings(root, context, warnings);
        List<AcEvidence> evidence = readEvidence(root, context, warnings);
        return new BatchAnswer(candidates, evidence, warnings);
    }

    private ReviewOutput read(String answer, Context context) {
        JsonNode root = parse(answer);
        List<String> warnings = new ArrayList<>();
        Map<Long, AcVerdict> reported = readVerdicts(root, context, warnings);
        List<FindingCandidate> findings = readFindings(root, context, warnings);

        List<AcResult> verdicts = new ArrayList<>();
        for (Context.Ac criterion : context.acceptanceCriteria()) {
            AcVerdict verdict = reported.get(criterion.id());
            if (verdict == null) {
                // 3.5: the model's omission becomes NOT_FOUND. The warning keeps
                // "the model said nothing" distinguishable from "the model said no".
                verdict = AcVerdict.NOT_FOUND;
                warnings.add("the answer skipped acceptance criterion " + criterion.key()
                        + ", recorded as NOT_FOUND");
            }
            verdicts.add(new AcResult(criterion.id(), criterion.key(), verdict));
        }
        return new ReviewOutput(verdicts, findings, warnings);
    }

    private JsonNode parse(String answer) {
        JsonNode root;
        try {
            root = json.readTree(answer);
        } catch (JacksonException notJson) {
            // The provider's own message can quote the answer, and the answer can
            // quote the prompt; neither may reach an operator-visible reason.
            throw new MalformedAnswer("the answer is not JSON");
        }
        if (!root.isObject()) {
            throw new MalformedAnswer("the answer is not a JSON object");
        }
        return root;
    }

    /**
     * A missing array is a structural failure, not an empty result. "The model
     * returned no findings" and "the model returned nothing shaped like an answer"
     * lead to opposite actions, and collapsing them is how a failed Review starts
     * looking like a clean pull request.
     */
    private static JsonNode requireArray(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            throw new MalformedAnswer("the answer carries no " + field + " array");
        }
        return value;
    }

    private Map<Long, AcVerdict> readVerdicts(JsonNode root, Context context, List<String> warnings) {
        Map<Long, AcVerdict> reported = new LinkedHashMap<>();
        for (JsonNode item : requireArray(root, "acVerdicts")) {
            Long acId = integralOrNull(item, "acId");
            AcVerdict verdict = verdictOf(stringOrNull(item, "verdict"));
            if (acId == null || verdict == null) {
                warnings.add("dropped a verdict with no usable acId or verdict");
                continue;
            }
            if (context.acceptanceCriterion(acId) == null) {
                // 3.5: the acId must belong to the revision under review. A criterion
                // from another revision reads plausibly and is about different text.
                warnings.add("dropped the verdict for acceptance criterion " + acId
                        + ": it does not belong to the revision under review");
                continue;
            }
            if (reported.putIfAbsent(acId, verdict) != null) {
                warnings.add("ignored a repeated verdict for acceptance criterion " + acId);
            }
        }
        return reported;
    }

    /** Deduplicated by {@code finding_key}, which is what 3.4.3 requires and what {@code uq_finding_review_key} enforces. */
    private List<FindingCandidate> readFindings(JsonNode root, Context context, List<String> warnings) {
        Map<String, FindingCandidate> byKey = new LinkedHashMap<>();
        for (JsonNode item : requireArray(root, "findings")) {
            FindingCandidate candidate = readFinding(item, context, warnings);
            if (candidate == null) {
                continue;
            }
            if (byKey.putIfAbsent(candidate.findingKey(), candidate) != null) {
                warnings.add("dropped a duplicate finding for " + candidate.path());
            }
        }
        return List.copyOf(byKey.values());
    }

    private FindingCandidate readFinding(JsonNode item, Context context, List<String> warnings) {
        FindingType type = typeOf(stringOrNull(item, "type"));
        if (type == null) {
            warnings.add("dropped a finding with no usable type");
            return null;
        }
        String path = stringOrNull(item, "path");
        ChangedFile file = context.visibleFile(path);
        if (file == null) {
            // 3.5: the path must be one of the changed files — and specifically one
            // this call was shown, so a batch cannot report on a file it never saw
            // and no finding can appear for a file the coverage manifest calls
            // unreviewed.
            warnings.add("dropped a " + type + " finding for " + path
                    + ": it is not one of the files this review was shown");
            return null;
        }
        String evidence = stringOrNull(item, "evidence");
        if (evidence == null || evidence.isBlank()) {
            // Without an excerpt there is nothing deterministic to hash, so every
            // evidence-less finding would share one evidence_hash and a rejection
            // of one could suppress an unrelated other.
            warnings.add("dropped a " + type + " finding for " + path + ": it carries no source excerpt");
            return null;
        }

        Long acId = integralOrNull(item, "acId");
        Context.Ac criterion = null;
        if (type == FindingType.REQUIREMENT) {
            criterion = acId == null ? null : context.acceptanceCriterion(acId);
            if (criterion == null) {
                warnings.add("dropped a REQUIREMENT finding for " + path + ": acceptance criterion " + acId
                        + " does not belong to the revision under review");
                return null;
            }
        } else if (acId != null) {
            // ck_finding_code_quality_has_no_ac would reject the row and take the
            // whole batch insert with it. Which of the two fields is wrong is not
            // knowable here, and guessing would invent a finding nobody reported.
            warnings.add("dropped a CODE_QUALITY finding for " + path + ": it cited acceptance criterion " + acId);
            return null;
        }

        List<String> excerptHashes = citedExcerptHashes(item, context, warnings, path);
        if (excerptHashes == null) {
            return null;
        }

        Integer line = verifiedLine(item, file);
        String category = stringOrNull(item, "category");
        return new FindingCandidate(type, path, line, evidence,
                // Copied from the Review, never read from the model: the constraint
                // trigger compares these two columns with the parent's, and a value
                // the model chose could only ever disagree.
                context.requirementId(), context.requirementRevisionId(),
                criterion == null ? null : criterion.id(),
                criterion == null ? null : criterion.key(),
                FindingKeys.findingKey(type, path, line, category,
                        criterion == null ? null : context.requirementId(),
                        criterion == null ? null : criterion.key()),
                FindingKeys.evidenceHash(evidence),
                // "被引用" is literal: a code-quality finding cites no requirement, so
                // publishing a new requirement revision must not drop its suppression.
                FindingKeys.basisHash(criterion == null ? null : context.requirementText(),
                        criterion == null ? null : criterion.key(),
                        criterion == null ? null : criterion.text(),
                        excerptHashes));
    }

    private List<AcEvidence> readEvidence(JsonNode root, Context context, List<String> warnings) {
        List<AcEvidence> evidence = new ArrayList<>();
        for (JsonNode item : requireArray(root, "acEvidence")) {
            Long acId = integralOrNull(item, "acId");
            Context.Ac criterion = acId == null ? null : context.acceptanceCriterion(acId);
            String path = stringOrNull(item, "path");
            ChangedFile file = context.visibleFile(path);
            String excerpt = stringOrNull(item, "excerpt");
            if (criterion == null || file == null || excerpt == null || excerpt.isBlank()) {
                warnings.add("dropped AC evidence for criterion " + acId + " in " + path);
                continue;
            }
            evidence.add(new AcEvidence(criterion.id(), criterion.key(), path,
                    verifiedLine(item, file), excerpt));
        }
        return List.copyOf(evidence);
    }

    /**
     * Returns the excerpt hashes of the knowledge sources this finding cited, or
     * {@code null} if it cited one that was never recalled for this Review (3.5:
     * {@code sourceId} must be in this round's whitelist). A false citation is not
     * repaired by dropping the citation — the claim rests on it.
     */
    private static List<String> citedExcerptHashes(JsonNode item, Context context,
            List<String> warnings, String path) {
        JsonNode sources = item.path("sourceIds");
        if (sources.isMissingNode() || sources.isNull()) {
            return List.of();
        }
        if (!sources.isArray()) {
            warnings.add("dropped a finding for " + path + ": sourceIds is not an array");
            return null;
        }
        List<String> hashes = new ArrayList<>();
        for (JsonNode source : sources) {
            String hash = source.isIntegralNumber()
                    ? context.knowledgeExcerptHashes().get(source.longValue())
                    : null;
            if (hash == null) {
                warnings.add("dropped a finding for " + path + ": it cited source " + source.asString()
                        + ", which this review did not recall");
                return null;
            }
            hashes.add(hash);
        }
        return hashes;
    }

    /**
     * The line the model gave, or {@code null} when the patch cannot confirm it.
     * 3.5 is explicit: an unverifiable line is not emitted. A plausible wrong line
     * sends a reviewer to the wrong place and looks exactly like a right one.
     */
    private static Integer verifiedLine(JsonNode item, ChangedFile file) {
        Long line = integralOrNull(item, "line");
        if (line == null || line < 1 || line > Integer.MAX_VALUE) {
            return null;
        }
        return isOnTheNewSide(file.patch(), line) ? line.intValue() : null;
    }

    /**
     * Walks a unified diff and asks whether {@code line} exists on its new side.
     *
     * <p>A file with no patch — binary, or past the provider's own diff limit —
     * verifies nothing, which is correct: nothing about its content was ever seen.
     * Anything unparsable also verifies nothing, so a diff shape this walk does not
     * understand loses precision rather than inventing it.
     */
    private static boolean isOnTheNewSide(String patch, long line) {
        if (patch == null) {
            return false;
        }
        long current = 0;
        boolean insideHunk = false;
        for (String row : patch.split("\n", -1)) {
            if (row.startsWith("@@")) {
                current = newSideStart(row);
                if (current < 0) {
                    return false;
                }
                insideHunk = true;
            } else if (insideHunk && (row.startsWith("+") || row.startsWith(" "))) {
                if (current == line) {
                    return true;
                }
                current++;
            }
            // Removed rows and the "\ No newline at end of file" marker occupy no
            // line on the new side, so they do not advance the counter.
        }
        return false;
    }

    private static long newSideStart(String hunkHeader) {
        int plus = hunkHeader.indexOf('+');
        if (plus < 0) {
            return -1;
        }
        int end = plus + 1;
        while (end < hunkHeader.length() && Character.isDigit(hunkHeader.charAt(end))) {
            end++;
        }
        int digits = end - plus - 1;
        return digits < 1 || digits > 9 ? -1 : Long.parseLong(hunkHeader.substring(plus + 1, end));
    }

    private static String stringOrNull(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static Long integralOrNull(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isIntegralNumber() ? value.longValue() : null;
    }

    private static AcVerdict verdictOf(String name) {
        for (AcVerdict verdict : AcVerdict.values()) {
            if (verdict.name().equals(name)) {
                return verdict;
            }
        }
        return null;
    }

    private static FindingType typeOf(String name) {
        for (FindingType type : FindingType.values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Everything one answer may be checked against: the Review's own requirement
     * context, the acceptance criteria of the revision it was created for, the
     * recall whitelist, and the files this particular call was shown.
     *
     * <p>All of it comes from the Review's immutable snapshot rather than from the
     * pull request's current state, so revalidating a historical answer cannot
     * change its meaning (3.5).
     */
    public record Context(Long requirementId, Long requirementRevisionId, String requirementText,
            List<Ac> acceptanceCriteria, Map<Long, String> knowledgeExcerptHashes,
            List<ChangedFile> visibleFiles) {

        public Context {
            if ((requirementId == null) != (requirementRevisionId == null)) {
                // The same pairing ck_review_requirement_pairing enforces: half of it
                // is what makes the composite foreign key skip its own check.
                throw new IllegalArgumentException(
                        "A review's requirement and revision are both present or both absent.");
            }
            if (requirementId == null && !acceptanceCriteria.isEmpty()) {
                throw new IllegalArgumentException("A review with no requirement has no acceptance criteria.");
            }
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
            knowledgeExcerptHashes = Map.copyOf(knowledgeExcerptHashes);
            visibleFiles = List.copyOf(visibleFiles);
        }

        /** {@code key} is {@code ac_key}: stable across revisions, unlike {@link #id()}. */
        public record Ac(long id, String key, String text) {
        }

        /** The same context narrowed to one batch's files. Everything else is the Review's, not the batch's. */
        Context withVisibleFiles(List<ChangedFile> files) {
            return new Context(requirementId, requirementRevisionId, requirementText,
                    acceptanceCriteria, knowledgeExcerptHashes, files);
        }

        Ac acceptanceCriterion(long acId) {
            for (Ac criterion : acceptanceCriteria) {
                if (criterion.id() == acId) {
                    return criterion;
                }
            }
            return null;
        }

        /** Case sensitive: {@code Api.java} and {@code api.java} are two files (3.4). */
        ChangedFile visibleFile(String path) {
            for (ChangedFile file : visibleFiles) {
                if (file.path().equals(path)) {
                    return file;
                }
            }
            return null;
        }
    }

    /**
     * The verdict on one answer, in the vocabulary of the execution state machine
     * itself (3.2: {@code RUNNING -> COMPLETED} when the output passes validation,
     * {@code RUNNING -> FAILED} when a repaired answer is still invalid).
     *
     * <p>The constructor is where "never a successful empty report" stops being a
     * rule and becomes a fact: a FAILED outcome cannot carry an output, and a
     * COMPLETED one cannot exist without it.
     */
    public record Outcome(ReviewStatus status, ReviewOutput output, String failureReason) {

        public Outcome {
            if (status == ReviewStatus.COMPLETED) {
                if (output == null || failureReason != null) {
                    throw new IllegalArgumentException("A completed validation carries its output and no reason.");
                }
            } else if (status == ReviewStatus.FAILED) {
                if (output != null || failureReason == null) {
                    throw new IllegalArgumentException("A failed validation carries a reason and no output.");
                }
            } else {
                throw new IllegalArgumentException("Validation ends in COMPLETED or FAILED.");
            }
        }

        static Outcome completed(ReviewOutput output) {
            return new Outcome(ReviewStatus.COMPLETED, output, null);
        }

        static Outcome failed(String reason) {
            return new Outcome(ReviewStatus.FAILED, null, reason);
        }
    }

    /** One batch's contribution: candidates and AC evidence, and no verdict (D002). */
    record BatchAnswer(List<FindingCandidate> candidates, List<AcEvidence> evidence, List<String> warnings) {
    }

    /** Evidence that one criterion is addressed somewhere in the diff. The synthesis, not the batch, concludes. */
    public record AcEvidence(long acId, String acKey, String path, Integer line, String excerpt) {
    }

    /** An answer whose structure cannot be used. Carries no model text: it reaches operator-visible output. */
    static final class MalformedAnswer extends RuntimeException {

        MalformedAnswer(String message) {
            super(message);
        }
    }
}
