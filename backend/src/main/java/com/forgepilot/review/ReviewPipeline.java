package com.forgepilot.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.forgepilot.ai.AiCallContext;
import com.forgepilot.ai.AiGateway;
import com.forgepilot.ai.AiUseCase;
import com.forgepilot.common.ApiException;
import com.forgepilot.knowledge.ChunkSearchRepository.ChunkMatch;
import com.forgepilot.knowledge.KnowledgeService;
import com.forgepilot.review.ChangedFileBatcher.Batch;
import com.forgepilot.review.ChangedFileBatcher.BatchPhase;
import com.forgepilot.review.ChangedFileBatcher.BatchReviewer;
import com.forgepilot.review.ChangedFileBatcher.Coverage;
import com.forgepilot.review.ChangedFileBatcher.Plan;
import com.forgepilot.review.FindingContinuityCalculator.Lineage;
import com.forgepilot.review.ReviewOutput.AcResult;
import com.forgepilot.review.ReviewOutput.FindingCandidate;
import com.forgepilot.review.ReviewOutputValidator.Context;
import com.forgepilot.review.ReviewOutputValidator.Outcome;
import com.forgepilot.review.ReviewPrompts.KnowledgeExcerpt;
import com.forgepilot.scm.ChangedFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One Review, from a claimed row to a stored report: ARCHITECTURE.md 3.3 and 3.4
 * end to end.
 *
 * <pre>
 * context  -> batches -> candidates + AC evidence -> one final synthesis
 *          -> validation -> lineage -> findings + summary
 * </pre>
 *
 * <p>Small and large pull requests travel this path identically. A one-file
 * change produces one batch and still goes through the synthesis, because a
 * shortcut that let a batch conclude would be the second pipeline 3.4 forbids.
 *
 * <p>Three rules here are absolute, and each of them is a way this can fail
 * <em>honestly</em> rather than look like it worked:
 *
 * <ul>
 * <li>A batch whose answer survives neither parsing nor its one repair fails the
 * whole Review, and nothing at all is written — no finding, no summary (3.4.4).
 * Three of five batches reported reads exactly like a clean pull request.</li>
 * <li>The synthesis answer that fails validation does the same (3.5). There is no
 * "successful empty report".</li>
 * <li>Findings and the terminal status commit together, which is what makes the
 * database fence effective: a stale attempt's findings are rejected by
 * {@code fk_finding_review}, and that rejection has to take the COMPLETED with
 * it.</li>
 * </ul>
 *
 * <p>Nothing here opens a transaction around a provider call. Each call may run
 * to the gateway's 120 s timeout and the pool has five connections; the reads
 * below each stand alone, and the one write is {@link #store}, which the executor
 * runs inside the transaction that also completes the Review.
 *
 * <p>The pull request, requirement and member reads are this feature's own SQL
 * rather than another feature's repository — ArchUnit rule 4, and the same shape
 * {@code DecisionRepository} already uses for the Decision preconditions.
 */
@Service
public class ReviewPipeline {

    private final ReviewRepository reviews;
    private final FindingRepository findings;
    private final ChangedFileBatcher batcher;
    private final ReviewOutputValidator validator;
    private final FindingContinuityCalculator continuity;
    private final KnowledgeService knowledge;
    private final AiGateway ai;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final String embeddingModel;
    private final String chatModel;
    private final int knowledgeTopK;

    ReviewPipeline(ReviewRepository reviews, FindingRepository findings, ChangedFileBatcher batcher,
            ReviewOutputValidator validator, FindingContinuityCalculator continuity,
            KnowledgeService knowledge, AiGateway ai, ObjectMapper json, JdbcTemplate jdbc,
            // The corpus was embedded with this model, so the query has to be too:
            // two models put the same sentence in two different spaces and the
            // distances stop meaning anything. Reading knowledge's own property is
            // the point rather than a leak — a second property here could drift.
            @Value("${forgepilot.knowledge.embedding.model:}") String embeddingModel,
            @Value("${forgepilot.ai.chat-model:}") String chatModel,
            @Value("${forgepilot.review.knowledge-top-k}") int knowledgeTopK) {
        this.reviews = reviews;
        this.findings = findings;
        this.batcher = batcher;
        this.validator = validator;
        this.continuity = continuity;
        this.knowledge = knowledge;
        this.ai = ai;
        this.json = json;
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.knowledgeTopK = knowledgeTopK;
    }

    /**
     * Reads this Review's inputs, runs every batch and then the one synthesis.
     *
     * <p>An empty result is a FAILED Review and carries nothing, so no caller can
     * store half a report by accident — the same shape {@link BatchPhase} and
     * {@link Outcome} already use.
     *
     * <p>{@code heartbeat} renews the lease before each provider call. It has to
     * exist: the lease is 300 s and a single call may take 120 s, so a three-batch
     * Review outlives its own claim and gets taken away mid-flight. Its result is
     * deliberately not consulted — every write after this point is fenced on the
     * token, so a lease already lost costs wasted work and cannot cost a wrong
     * write, and a branch here would only duplicate that guarantee.
     */
    public Optional<Report> analyse(ReviewExecutor.Claim claim, Runnable heartbeat) {
        Review review = reviews.findByProjectIdAndId(claim.projectId(), claim.reviewId())
                .orElseThrow(ApiException::notFound);
        JsonNode snapshot = snapshotOf(review);
        String requirementText = requirementTextOf(snapshot);
        List<Context.Ac> criteria = acceptanceCriteriaOf(snapshot);
        Plan plan = batcher.plan(changedFilesOf(snapshot));
        List<KnowledgeExcerpt> recalled = recall(review, requirementText, criteria, plan);

        // Everything an answer is checked against, and all of it from this Review's
        // own row rather than the pull request's present state (3.5). The visible
        // files are the ones the plan will actually send: a finding about a file
        // the coverage manifest calls unreviewed must not be storable.
        Context context = new Context(review.getRequirementId(), review.getRequirementRevisionId(),
                requirementText, criteria, excerptHashesOf(recalled), reviewedFiles(plan));
        AiCallContext callContext = callContextOf(review);

        BatchPhase phase = batcher.run(plan, context, reviewer(context, recalled, callContext, heartbeat));
        if (phase.status() == ReviewStatus.FAILED) {
            return Optional.empty();
        }

        heartbeat.run();
        // One call, over every batch's candidates and evidence at once. This is
        // where AC verdicts are decided and nowhere else (D002).
        Outcome outcome = validator.validate(
                ai.chat(ReviewPrompts.synthesis(context, recalled, phase.candidates(), phase.evidence(),
                        plan.coverage()), ReviewPrompts.SYNTHESIS_SCHEMA, AiUseCase.REVIEW, callContext),
                malformed -> ai.chat(ReviewPrompts.repair(malformed), ReviewPrompts.SYNTHESIS_SCHEMA,
                        AiUseCase.REVIEW, callContext),
                context);
        if (outcome.status() == ReviewStatus.FAILED) {
            return Optional.empty();
        }
        return Optional.of(new Report(withBatchWarnings(outcome.output(), phase.warnings()),
                plan.coverage(), recalled));
    }

    /**
     * Writes the findings and the summary.
     *
     * <p>{@code MANDATORY} rather than {@code REQUIRED}: this must run inside the
     * transaction that also marks the Review COMPLETED. If it ever opened its own,
     * a report could commit under a Review that then failed to complete — and the
     * database fence, which rejects a stale attempt's findings, would no longer be
     * able to take the terminal status down with them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void store(ReviewExecutor.Claim claim, Report report) {
        Review review = reviews.findByProjectIdAndId(claim.projectId(), claim.reviewId())
                .orElseThrow(ApiException::notFound);
        List<FindingCandidate> candidates = report.output().findings();
        Map<String, Lineage> lineages = continuity.lineageOf(claim.projectId(),
                review.getPullRequestId(), claim.reviewId(), candidates);

        List<Finding> rows = new ArrayList<>();
        for (FindingCandidate candidate : candidates) {
            Lineage lineage = lineages.get(candidate.findingKey());
            // The attempt is the claim's, never the row's: fk_finding_review points
            // at (project_id, id, execution_attempt), so a worker whose Review was
            // re-claimed is refused here by the database rather than by a check.
            Finding finding = new Finding(claim.projectId(), claim.reviewId(), claim.attempt(),
                    candidate.requirementId(), candidate.requirementRevisionId(), candidate.acId(),
                    candidate.findingType(), candidate.path(), candidate.line(), candidate.evidence(),
                    candidate.findingKey(), candidate.evidenceHash(), candidate.basisHash(),
                    lineage.continuity(), lineage.carriedFromFindingId());
            if (lineage.initialStatus() == FindingStatus.REJECTED) {
                // 3.6.4: an inherited suppression starts life already rejected, so a
                // false positive a person dismissed does not reappear as OPEN work.
                finding.startSuppressed();
            }
            rows.add(finding);
        }
        findings.saveAllAndFlush(rows);

        // Not fenced on the token, and it does not need to be: this statement and
        // the executor's fenced completion are one transaction, so a lost lease
        // rolls this back with the completion that failed to match.
        jdbc.update("""
                update review
                   set summary_json = cast(? as jsonb), engine = ?, prompt_version = ?, model = ?,
                       updated_at = now()
                 where project_id = ? and id = ?
                """,
                json.writeValueAsString(new Summary(report.output().acVerdicts(), report.coverage(),
                        report.knowledgeEvidence(), report.output().warnings())),
                ReviewPrompts.ENGINE, ReviewPrompts.VERSION, chatModel,
                claim.projectId(), claim.reviewId());
    }

    // ------------------------------------------------------------------ context

    /**
     * The revision's prose as one deterministic string. It is not only the prompt's
     * requirement section — it is also an input to every {@code basis_hash}, so how
     * it is assembled has to stay fixed: a change to the joining here silently
     * drops every inherited suppression in the product.
     */
    private JsonNode snapshotOf(Review review) {
        if (review.getContextSnapshotJson() == null) {
            throw ApiException.conflict("This review has no immutable input snapshot.");
        }
        return json.readTree(review.getContextSnapshotJson());
    }

    private static String requirementTextOf(JsonNode snapshot) {
        JsonNode requirement = snapshot.path("requirement");
        if (requirement.isMissingNode() || requirement.isNull()) {
            return null;
        }
        return Stream.of(requirement.path("title"), requirement.path("background"),
                        requirement.path("description"))
                .filter(JsonNode::isString)
                .map(JsonNode::stringValue)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    /** Ordered by {@code sort_order} for display; {@code ac_key}, never the row id, is the identity (D011). */
    private static List<Context.Ac> acceptanceCriteriaOf(JsonNode snapshot) {
        List<Context.Ac> criteria = new ArrayList<>();
        for (JsonNode criterion : snapshot.path("acceptanceCriteria")) {
            criteria.add(new Context.Ac(criterion.path("id").longValue(),
                    criterion.path("acKey").stringValue(), criterion.path("text").stringValue()));
        }
        return List.copyOf(criteria);
    }

    /**
     * The stored manifest, not a fresh fetch from the provider. It is what
     * {@code review_input_fingerprint} was computed over, so re-reading it is the
     * only way this Review reviews the diff its identity names.
     */
    private static List<ChangedFile> changedFilesOf(JsonNode snapshot) {
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode file : snapshot.path("changedFiles")) {
            JsonNode patch = file.path("patch");
            // Absent and empty are different facts about a file (D015.7), so a
            // missing patch stays null and the batcher reports it as unreviewed.
            files.add(new ChangedFile(file.path("path").stringValue(),
                    file.path("changeType").stringValue(),
                    patch.isString() ? patch.stringValue() : null));
        }
        return files;
    }

    /**
     * The project knowledge this Review is allowed to cite (3.3). The query is the
     * requirement, its criteria and the paths that will actually be reviewed.
     *
     * <p>A blank query recalls nothing rather than embedding an empty string: the
     * search returns the nearest chunks to whatever vector it is given, so an empty
     * query would not return "no knowledge", it would return arbitrary knowledge
     * and let the model cite it.
     */
    private List<KnowledgeExcerpt> recall(Review review, String requirementText,
            List<Context.Ac> criteria, Plan plan) {
        StringBuilder query = new StringBuilder();
        if (requirementText != null) {
            query.append(requirementText).append('\n');
        }
        criteria.forEach(criterion -> query.append(criterion.text()).append('\n'));
        plan.coverage().files().forEach(file -> query.append(file.path()).append('\n'));
        if (query.isEmpty()) {
            return List.of();
        }

        float[] vector = ai.embed(List.of(query.toString()), embeddingModel,
                AiCallContext.ofProject(review.getProjectId())).getFirst();
        List<KnowledgeExcerpt> recalled = new ArrayList<>();
        for (ChunkMatch match : knowledge.search(review.getProjectId(), retrievalActor(review),
                vector, knowledgeTopK)) {
            recalled.add(new KnowledgeExcerpt(match.id(), match.documentId(), match.id(),
                    match.content(), 1.0d - match.distance()));
        }
        return recalled;
    }

    /**
     * Whose membership the retrieval is checked against.
     *
     * <p>An automatic Review has no human actor, and {@code KnowledgeService.search}
     * requires one because its authorization is written for API callers. The
     * project's LEADER stands in: D004 guarantees exactly one, and the search itself
     * is project-scoped, so the actor changes nothing about the result — it only
     * satisfies a membership check the engine trivially passes. The honest fix is a
     * retrieval entry point on {@code knowledge} that takes no actor, which is a
     * change to a file this slice may not touch.
     */
    private long retrievalActor(Review review) {
        return jdbc.queryForObject(
                "select user_id from project_member where project_id = ? and role = 'LEADER'",
                Long.class, review.getProjectId());
    }

    /**
     * {@code sourceId -> excerpt hash}, which is both the citation whitelist (3.5)
     * and half of every {@code basis_hash} (3.6.2). The excerpt's text is hashed
     * rather than stored in the key so that editing the knowledge document later
     * cannot rewrite what a past Review was judged against.
     */
    private static Map<Long, String> excerptHashesOf(List<KnowledgeExcerpt> recalled) {
        Map<Long, String> hashes = new LinkedHashMap<>();
        recalled.forEach(excerpt ->
                hashes.put(excerpt.sourceId(), FindingKeys.evidenceHash(excerpt.excerpt())));
        return hashes;
    }

    /** Only the files the plan will send. Anything else is in the coverage manifest as unreviewed. */
    private static List<ChangedFile> reviewedFiles(Plan plan) {
        return plan.batches().stream().flatMap(batch -> batch.files().stream()).toList();
    }

    private static AiCallContext callContextOf(Review review) {
        return review.getRequirementId() == null
                ? AiCallContext.ofProject(review.getProjectId())
                : AiCallContext.ofRevision(review.getProjectId(), review.getRequirementId(),
                        review.getRequirementRevisionId());
    }

    // --------------------------------------------------------------- the calls

    /**
     * How a batch reaches the model, and the one format repair 3.5 allows. The
     * repair asks for a conversion of the answer already given; the batcher calls
     * it at most once per batch and fails the Review if it does not take.
     */
    private BatchReviewer reviewer(Context context, List<KnowledgeExcerpt> recalled,
            AiCallContext callContext, Runnable heartbeat) {
        return new BatchReviewer() {

            @Override
            public String review(Batch batch) {
                heartbeat.run();
                return ai.chat(ReviewPrompts.batch(context, recalled, batch),
                        ReviewPrompts.BATCH_SCHEMA, AiUseCase.REVIEW, callContext);
            }

            @Override
            public String repair(Batch batch, String malformedAnswer) {
                return ai.chat(ReviewPrompts.repair(malformedAnswer),
                        ReviewPrompts.BATCH_SCHEMA, AiUseCase.REVIEW, callContext);
            }
        };
    }

    /**
     * Carries the batch phase's warnings into the stored report. Dropping them
     * would leave a shorter report with no trace of having been shortened, which is
     * the rule D002 states for unreviewed files applied to unusable claims.
     */
    private static ReviewOutput withBatchWarnings(ReviewOutput output, List<String> batchWarnings) {
        List<String> warnings = new ArrayList<>(batchWarnings);
        warnings.addAll(output.warnings());
        return new ReviewOutput(output.acVerdicts(), output.findings(), warnings);
    }

    /**
     * What one completed Review produced. A FAILED Review has no {@code Report} at
     * all rather than an empty one, so "never a partial report" is a fact about the
     * types instead of a rule somebody has to remember.
     */
    public record Report(ReviewOutput output, Coverage coverage, List<KnowledgeExcerpt> knowledgeEvidence) {

        public Report {
            knowledgeEvidence = List.copyOf(knowledgeEvidence);
        }
    }

    /**
     * {@code review.summary_json}. Output, deliberately kept apart from the input
     * snapshot in {@code context_snapshot_json} (3.5).
     *
     * <p>{@code coverage} lives here rather than in the snapshot because that is
     * where {@code ReviewDecisionService} reads it from, and D002 requires an empty
     * {@code notReviewed} and a missing one to stay distinguishable — so the field
     * is always written, even when nothing was cut.
     */
    private record Summary(List<AcResult> acVerdicts, Coverage coverage,
            List<KnowledgeExcerpt> knowledgeEvidence, List<String> warnings) {
    }
}
