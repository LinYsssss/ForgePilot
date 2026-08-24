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
 * 一次 Review 的完整流程：从一行被抢占的记录，到一份落库的报告，
 * 对应 ARCHITECTURE.md 3.3 与 3.4 的全过程。
 *
 * <pre>
 * 上下文 -> 分批 -> 候选项 + AC 证据 -> 一次最终综合
 *        -> 校验 -> 血缘 -> finding + 摘要
 * </pre>
 *
 * <p>小 PR 与大 PR 走的是完全相同的路径。一个只改了一个文件的变更会产生一个批次，
 * 并且仍然要经过综合阶段——因为一条「让批次直接下结论」的捷径，
 * 就是 3.4 明令禁止的那第二条流水线。
 *
 * <p>这里有三条绝对规则，每一条都是一种让它<em>诚实地失败</em>、
 * 而不是「看起来成功了」的方式：
 *
 * <ul>
 * <li>某个批次的回答既解析不了、那一次修复也救不回来时，整次 Review 失败，
 * 且什么都不写——没有 finding，也没有摘要（3.4.4）。
 * 「五个批次报告了三个」读起来与「PR 很干净」一模一样。</li>
 * <li>综合阶段的回答没通过校验时同理（3.5）。这里不存在“成功的空报告”。</li>
 * <li>finding 与终态**一起**提交，而这正是数据库那道围栏之所以有效的原因：
 * 过期 attempt 的 finding 会被 {@code fk_finding_review} 拒绝，
 * 而这次拒绝必须把 COMPLETED 一起带走。</li>
 * </ul>
 *
 * <p>这里不在任何 provider 调用外面开事务。每次调用都可能跑满网关的 120 秒超时，
 * 而连接池只有五个连接；下面的各次读取都各自独立完成，
 * 唯一的写入是 {@link #store}，由执行器放在「同时把 Review 置为完成」的
 * 那个事务里运行。
 *
 * <p>对 PR、需求与成员的读取用的是本功能模块自己的 SQL，而不是另一个模块的仓库
 * ——这是 ArchUnit 规则 4，也是 {@code DecisionRepository} 为 Decision 前置条件
 * 已经采用的同一形态。
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
            // 语料是用这个模型做的向量化，因此查询也必须用它：两个模型会把同一句话
            // 放进两个不同的空间，距离也就不再有任何意义。读取 knowledge 自己的
            // 配置项正是要点所在，而不是什么泄漏——在这里另设一个属性反而会漂移。
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
     * 读取本次 Review 的输入，跑完每一个批次，然后做那一次综合。
     *
     * <p>返回空即表示这是一次 FAILED 的 Review，且不携带任何东西，
     * 因此没有任何调用方能意外地存下半份报告——
     * 与 {@link BatchPhase} 和 {@link Outcome} 采用的是同一形态。
     *
     * <p>{@code heartbeat} 在每次 provider 调用之前续租。它必须存在：
     * 租约是 300 秒，而单次调用可能耗时 120 秒，于是一个三批次的 Review
     * 会活得比自己的抢占还久，从而在半途被别人夺走。
     * 它的返回值**刻意**不予理会——此后的每一次写入都以 token 设了围栏，
     * 因此一个已经丢失的租约只会白费工夫，绝不可能造成一次错误写入；
     * 在这里加个分支，只会把那条保证重复一遍。
     */
    public Optional<Report> analyse(ReviewExecutor.Claim claim, Runnable heartbeat) {
        Review review = reviews.findByProjectIdAndId(claim.projectId(), claim.reviewId())
                .orElseThrow(ApiException::notFound);
        JsonNode snapshot = snapshotOf(review);
        String requirementText = requirementTextOf(snapshot);
        List<Context.Ac> criteria = acceptanceCriteriaOf(snapshot);
        Plan plan = batcher.plan(changedFilesOf(snapshot));
        List<KnowledgeExcerpt> recalled = recall(review, requirementText, criteria, plan);

        // 一个回答要被对照检查的全部内容，且全部来自本次 Review 自己的那一行，
        // 而不是 PR 的当前状态（3.5）。可见文件就是计划将要真正发送出去的那些：
        // 一条针对「覆盖清单标为未审查」的文件的 finding，绝不能被存下来。
        Context context = new Context(review.getRequirementId(), review.getRequirementRevisionId(),
                requirementText, criteria, excerptHashesOf(recalled), reviewedFiles(plan));
        AiCallContext callContext = callContextOf(review);

        BatchPhase phase = batcher.run(plan, context, reviewer(context, recalled, callContext, heartbeat));
        if (phase.status() == ReviewStatus.FAILED) {
            return Optional.empty();
        }

        heartbeat.run();
        // 一次调用，一次性覆盖所有批次的候选项与证据。
        // AC 裁定只在这里决定，别处一律不行（D002）。
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
     * 写入 finding 与摘要。
     *
     * <p>用 {@code MANDATORY} 而不是 {@code REQUIRED}：它必须跑在那个同时把
     * Review 标记为 COMPLETED 的事务里。一旦它自己开了事务，
     * 就可能出现「报告提交了、而那次 Review 随后没能完成」——
     * 而那道拒绝过期 attempt 的数据库围栏，也就再也无法把终态一并带下去了。
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
            // attempt 取自本次抢占，绝不取自那一行：fk_finding_review 指向
            // (project_id, id, execution_attempt)，因此一个 Review 已被重新抢占的
            // worker，会在这里被**数据库**拒绝，而不是被某个检查拒绝。
            Finding finding = new Finding(claim.projectId(), claim.reviewId(), claim.attempt(),
                    candidate.requirementId(), candidate.requirementRevisionId(), candidate.acId(),
                    candidate.findingType(), candidate.path(), candidate.line(), candidate.evidence(),
                    candidate.findingKey(), candidate.evidenceHash(), candidate.basisHash(),
                    lineage.continuity(), lineage.carriedFromFindingId());
            if (lineage.initialStatus() == FindingStatus.REJECTED) {
                // 3.6.4：被继承的抑制项一出生就是被驳回状态，
                // 因此一个已经被人驳掉的误报，不会再作为 OPEN 的待办重新冒出来。
                finding.startSuppressed();
            }
            rows.add(finding);
        }
        findings.saveAllAndFlush(rows);

        // 这里没有以 token 设围栏，也不需要：本语句与执行器那次带围栏的完成操作
        // 处于同一个事务，因此租约一旦丢失，这条写入会与那次匹配失败的完成操作
        // 一起回滚。
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

    // ------------------------------------------------------------------ 上下文

    /**
     * 把修订的文本拼成一个确定性的字符串。它不只是 Prompt 的需求段落——
     * 它同时也是每一个 {@code basis_hash} 的输入，因此它的拼装方式必须固定不变：
     * 改动这里的拼接，会静默地丢掉产品中所有被继承的抑制项。
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

    /** 按 {@code sort_order} 排序以供展示；身份始终是 {@code ac_key}，绝不是行 id（D011）。 */
    private static List<Context.Ac> acceptanceCriteriaOf(JsonNode snapshot) {
        List<Context.Ac> criteria = new ArrayList<>();
        for (JsonNode criterion : snapshot.path("acceptanceCriteria")) {
            criteria.add(new Context.Ac(criterion.path("id").longValue(),
                    criterion.path("acKey").stringValue(), criterion.path("text").stringValue()));
        }
        return List.copyOf(criteria);
    }

    /**
     * 用的是**已存下来的**清单，而不是重新去 provider 拉一次。
     * {@code review_input_fingerprint} 正是基于它计算的，
     * 因此重读它是本次 Review 得以审查「它的身份所指名的那份 diff」的唯一方式。
     */
    private static List<ChangedFile> changedFilesOf(JsonNode snapshot) {
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode file : snapshot.path("changedFiles")) {
            JsonNode patch = file.path("patch");
            // 对一个文件而言，「缺席」与「空」是两个不同的事实（D015.7），
            // 因此缺失的 patch 保持为 null，由分批器把它报告为未审查。
            files.add(new ChangedFile(file.path("path").stringValue(),
                    file.path("changeType").stringValue(),
                    patch.isString() ? patch.stringValue() : null));
        }
        return files;
    }

    /**
     * 本次 Review 被允许引用的项目知识（3.3）。查询串由需求、它的验收条件
     * 以及那些真正会被审查的路径构成。
     *
     * <p>空白查询会「什么都不召回」，而不是去把空串做向量化：检索会返回距离
     * 给定向量最近的那些分块，因此一个空查询返回的不是「没有知识」，
     * 而是**任意的**知识，还会让模型去引用它。
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
                review.getRequirementId(), vector, knowledgeTopK)) {
            recalled.add(new KnowledgeExcerpt(match.id(), match.documentId(), match.id(),
                    match.content(), 1.0d - match.distance()));
        }
        return recalled;
    }

    /**
     * 这次检索是拿谁的成员身份去校验的。
     *
     * <p>自动触发的 Review 没有人类操作者，而 {@code KnowledgeService.search}
     * 需要一个——因为它的鉴权是照着 API 调用方写的。这里由项目的 LEADER 顶上：
     * D004 保证它恰好只有一个，而检索本身就是项目内限定的，
     * 因此这个 actor 不改变结果的任何部分——它只是满足了一次引擎本来就轻松通过的
     * 成员校验。诚实的修法是在 {@code knowledge} 上开一个不需要 actor 的检索入口，
     * 而那要改的文件不在本切片可以触碰的范围内。
     */
    private long retrievalActor(Review review) {
        return jdbc.queryForObject(
                "select user_id from project_member_role where project_id = ? and role = 'LEADER'",
                Long.class, review.getProjectId());
    }

    /**
     * {@code sourceId -> 片段哈希}，它既是引用白名单（3.5），
     * 也是每一个 {@code basis_hash} 的一半（3.6.2）。片段的文本是被**哈希**、
     * 而不是被存进这个键里的，从而使日后编辑知识文档无法改写
     * 一次过往 Review 当初所对照的依据。
     */
    private static Map<Long, String> excerptHashesOf(List<KnowledgeExcerpt> recalled) {
        Map<Long, String> hashes = new LinkedHashMap<>();
        recalled.forEach(excerpt ->
                hashes.put(excerpt.sourceId(), FindingKeys.evidenceHash(excerpt.excerpt())));
        return hashes;
    }

    /** 只包含计划将要发送的那些文件。其余一律以「未审查」的身份出现在覆盖清单里。 */
    private static List<ChangedFile> reviewedFiles(Plan plan) {
        return plan.batches().stream().flatMap(batch -> batch.files().stream()).toList();
    }

    private static AiCallContext callContextOf(Review review) {
        return review.getRequirementId() == null
                ? AiCallContext.ofProject(review.getProjectId())
                : AiCallContext.ofRevision(review.getProjectId(), review.getRequirementId(),
                        review.getRequirementRevisionId());
    }

    // --------------------------------------------------------------- 模型调用

    /**
     * 一个批次是如何抵达模型的，以及 3.5 允许的那一次格式修复。
     * 修复要求的是对**已经给出的**那个回答做一次转换；分批器每个批次最多调用它一次，
     * 修复不奏效就让整次 Review 失败。
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
     * 把分批阶段的警告带进最终落库的报告里。丢掉它们，只会留下一份更短的报告，
     * 却没有任何被缩短过的痕迹——这正是 D002 对「未审查文件」立下的规则，
     * 应用到了「无法使用的断言」上。
     */
    private static ReviewOutput withBatchWarnings(ReviewOutput output, List<String> batchWarnings) {
        List<String> warnings = new ArrayList<>(batchWarnings);
        warnings.addAll(output.warnings());
        return new ReviewOutput(output.acVerdicts(), output.findings(), warnings);
    }

    /**
     * 一次已完成的 Review 产出了什么。FAILED 的 Review 是**根本没有**
     * {@code Report}，而不是有一个空的——因此「绝无残缺报告」是一个关于类型的事实，
     * 而不是一条要靠人记住的规则。
     */
    public record Report(ReviewOutput output, Coverage coverage, List<KnowledgeExcerpt> knowledgeEvidence) {

        public Report {
            knowledgeEvidence = List.copyOf(knowledgeEvidence);
        }
    }

    /**
     * {@code review.summary_json}。这是**输出**，刻意与
     * {@code context_snapshot_json} 里的输入快照分开存放（3.5）。
     *
     * <p>{@code coverage} 放在这里而不是快照里，是因为
     * {@code ReviewDecisionService} 正是从这里读它的；
     * 而 D002 要求「空的 {@code notReviewed}」与「缺失的 {@code notReviewed}」
     * 必须保持可区分——因此这个字段**总是**写入，哪怕什么都没被裁掉。
     */
    private record Summary(List<AcResult> acVerdicts, Coverage coverage,
            List<KnowledgeExcerpt> knowledgeEvidence, List<String> warnings) {
    }
}
