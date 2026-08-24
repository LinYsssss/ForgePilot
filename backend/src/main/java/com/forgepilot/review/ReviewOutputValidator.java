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
 * 把模型的一次回答，变成一个对本次 Review 自身上下文而言为真的
 * {@link ReviewOutput}，或者变成一个 FAILED 裁定（ARCHITECTURE.md 3.5）。
 *
 * <p>这里的一切由两条规则塑形：
 *
 * <ul>
 * <li>被审查修订的**每一条**验收条件最终都会有一个裁定。模型跳过的条件
 * 会被填成 {@code NOT_FOUND}，因此一个沉默的模型永远无法把报告缩短。</li>
 * <li>结构非法的回答可以得到<strong>一次</strong>格式修复。修复仍不成功，
 * 则该 Review 判为 FAILED——绝不产出一份读起来像「PR 很干净」的空报告。
 * {@link Outcome} 让这一点从「本意如此」变成了**不可表达**。</li>
 * </ul>
 *
 * <p>单条断言的处理方式与结构性失败不同：模型编造出来的引用会被丢弃，
 * 并记入 {@link ReviewOutput#warnings()}，而不是让整次 Review 失败。
 * 这同时也是批量插入得以保持完整的原因——一条上下文与父行矛盾的 Finding
 * 会在约束触发器处中止整批插入，因此校验发生在写入**之前**，
 * 而触发器始终是最后一道防线，不是第一道（design.md 4.4）。
 */
@Component
public class ReviewOutputValidator {

    /**
     * 散文字段的上限，沿用本代码库既有的散文上限约定
     * （{@code FindingController.StatusRequest.comment} 与前端 textarea 同为 2000），
     * 不另立一个数字。它同时写在两个 schema 的 {@code maxLength} 里；
     * 这里是模型不遵守时的那道防线。
     */
    private static final int MAX_PROSE = 2000;

    private final ObjectMapper json;

    ReviewOutputValidator(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 校验最终综合阶段的回答，允许**恰好一次**格式修复。
     *
     * <p>{@code formatRepair} 接收那个解析不了的回答，并返回模型的第二次尝试。
     * 它最多被调用一次：3.5 只给一次修复机会，
     * 而在这里写个循环，会悄悄把 AI 网关自身那次有界重试放大成四次调用。
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
     * 校验单个批次的回答。批次只报告候选项与证据，<strong>绝不</strong>给出裁定
     * （D002）：它只看到了 diff 的一部分，因此它对某条验收条件下的结论
     * 会与另一个批次矛盾。所以任何 {@code acVerdicts} 字段这里连读都不读。
     *
     * <p>它抛异常而不是返回一个 outcome，因为修复预算归 {@link ChangedFileBatcher}
     * 所有——后者拥有整个分批阶段，且在修复不奏效时必须让**整次 Review** 失败，
     * 而不只是这一个批次。
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
                // 3.5：模型的遗漏被填成 NOT_FOUND。而那条警告，
                // 让「模型什么都没说」与「模型说了没有」保持可区分。
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
            // provider 自己的报错消息可能引用回答，而回答又可能引用 Prompt；
            // 这两者都不得出现在运维可见的失败原因里。
            throw new MalformedAnswer("the answer is not JSON");
        }
        if (!root.isObject()) {
            throw new MalformedAnswer("the answer is not a JSON object");
        }
        return root;
    }

    /**
     * 数组缺失是结构性失败，不是空结果。「模型返回了零条 finding」与
     * 「模型返回的东西根本不像一个回答」会导向相反的处置，
     * 而把二者混为一谈，正是一次失败的 Review 开始看起来像「PR 很干净」的方式。
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
                // 3.5：acId 必须属于本次被审查的那个修订。来自另一个修订的
                // 验收条件读起来同样合情合理，但它针对的是另一段文本。
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

    /** 按 {@code finding_key} 去重——这既是 3.4.3 的要求，也是 {@code uq_finding_review_key} 所强制的。 */
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
            // 3.5：路径必须是变更文件之一——而且必须是**本次调用被展示过**的那些，
            // 于是一个批次无法对它从未见过的文件作出报告，
            // 也不会有任何 finding 出现在覆盖清单标为「未审查」的文件上。
            warnings.add("dropped a " + type + " finding for " + path
                    + ": it is not one of the files this review was shown");
            return null;
        }
        String evidence = stringOrNull(item, "evidence");
        if (evidence == null || evidence.isBlank()) {
            // 没有证据片段就没有确定性的东西可供哈希，于是所有无证据的 finding
            // 会共享同一个 evidence_hash，对其中一条的驳回就可能抑制掉
            // 另一条毫不相干的。
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
            // ck_finding_code_quality_has_no_ac 会拒绝这一行，并把整批插入一起带走。
            // 这两个字段到底哪个错了，在这里无从得知，
            // 而猜一个，就等于凭空造出一条没人报告过的 finding。
            warnings.add("dropped a CODE_QUALITY finding for " + path + ": it cited acceptance criterion " + acId);
            return null;
        }

        List<String> excerptHashes = citedExcerptHashes(item, context, warnings, path);
        if (excerptHashes == null) {
            return null;
        }

        Integer line = verifiedLine(item, file);
        // key 用的是模型给出的原始字符串（在 FindingKeys 里经 normalizeCategory
        // 折叠），落库用的是映射到词表之后的值。两者刻意不同，见 FindingCategory。
        String reportedCategory = stringOrNull(item, "category");
        return new FindingCandidate(type, path, line, evidence,
                category(reportedCategory, warnings, path),
                prose(item, "explanation", warnings, path),
                prose(item, "suggestion", warnings, path),
                confidence(item, warnings, path),
                // 从 Review 复制而来，绝不从模型读取：约束触发器会把这两列与父行
                // 比对，而由模型挑出来的值只可能与之矛盾。
                context.requirementId(), context.requirementRevisionId(),
                criterion == null ? null : criterion.id(),
                criterion == null ? null : criterion.key(),
                FindingKeys.findingKey(type, path, line, reportedCategory,
                        criterion == null ? null : context.requirementId(),
                        criterion == null ? null : criterion.key()),
                FindingKeys.evidenceHash(evidence),
                // “被引用”是字面意思：代码质量类 finding 不引用任何需求，
                // 因此发布一个新的需求修订不得让它的抑制项失效。
                FindingKeys.basisHash(criterion == null ? null : context.requirementText(),
                        criterion == null ? null : criterion.key(),
                        criterion == null ? null : criterion.text(),
                        excerptHashes));
    }

    /**
     * 模型写的散文，缺席时为 {@code null}，超出 {@link #MAX_PROSE} 则截断。
     * 两种情形都留下警告，且<strong>都不会丢弃这条 finding</strong>。
     *
     * <p>丢弃整条留给确定性部分不可用的情形——例如没有 evidence，那会让所有
     * 无证据的 finding 共享同一个 {@code evidence_hash}。散文进不了任何哈希，
     * 所以为它丢掉一条有证据的有效 finding 是纯粹的损失；而为啰嗦而拒绝
     * 是同一种损失换了个理由，因此超限是截断而不是拒绝。
     */
    private static String prose(JsonNode item, String field, List<String> warnings, String path) {
        String value = stringOrNull(item, field);
        if (value == null || value.isBlank()) {
            warnings.add("a finding for " + path + " carries no " + field);
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() <= MAX_PROSE) {
            return stripped;
        }
        warnings.add("truncated the " + field + " of a finding for " + path
                + " at " + MAX_PROSE + " characters");
        return stripped.substring(0, MAX_PROSE);
    }

    /**
     * 落库用的类别，词表外或缺席时为 {@code null}。
     *
     * <p>越界值绝不能原样送到 {@code ck_finding_category}：走到约束那里的值
     * 中止的是**整批**插入而不是这一行（同 {@code ck_finding_code_quality_has_no_ac}
     * 的形态）。这里丢掉的只是落库的那一份，{@code finding_key} 仍由原始字符串
     * 算出，因此不因本次丢弃而改变。
     */
    private static FindingCategory category(String reported, List<String> warnings, String path) {
        FindingCategory category = FindingCategory.of(reported);
        if (category == null) {
            warnings.add("dropped the category of a finding for " + path
                    + (reported == null ? ": the answer carried none"
                            : ": it is outside the category vocabulary"));
        }
        return category;
    }

    /** 落库用的置信度档位，词表外或缺席时为 {@code null}（同样不丢弃这条 finding）。 */
    private static FindingConfidence confidence(JsonNode item, List<String> warnings, String path) {
        String reported = stringOrNull(item, "confidence");
        FindingConfidence confidence = FindingConfidence.of(reported);
        if (confidence == null) {
            warnings.add("dropped the confidence of a finding for " + path
                    + (reported == null ? ": the answer carried none"
                            : ": it is outside the confidence vocabulary"));
        }
        return confidence;
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
     * 返回这条 finding 所引用的知识来源的片段哈希；若它引用了某个本次 Review
     * 根本没有召回过的来源，则返回 {@code null}（3.5：{@code sourceId} 必须在
     * 本轮的白名单里）。一次虚假引用不能靠「把引用删掉」来修复——
     * 它的整个断言正是架在那条引用上的。
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
     * 模型给出的行号；若 patch 无法确认它则返回 {@code null}。
     * 3.5 说得很明确：无法核实的行号不予输出。一个看似合理的错误行号
     * 会把 reviewer 引向错误的位置，而它看起来与正确行号一模一样。
     */
    private static Integer verifiedLine(JsonNode item, ChangedFile file) {
        Long line = integralOrNull(item, "line");
        if (line == null || line < 1 || line > Integer.MAX_VALUE) {
            return null;
        }
        return isOnTheNewSide(file.patch(), line) ? line.intValue() : null;
    }

    /**
     * 遍历一份 unified diff，判断 {@code line} 是否存在于它的新侧。
     *
     * <p>没有 patch 的文件——二进制，或超出 provider 自身 diff 上限——
     * 什么都核实不了，而这是对的：它的内容从来没有被看到过。
     * 解析不了的内容同样什么都核实不了，因此本遍历看不懂的 diff 形态
     * 只会损失精确率，而不会凭空编造。
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
            // 被删除的行以及 "\ No newline at end of file" 标记在新侧不占任何行，
            // 因此它们不推进计数器。
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
     * 一次回答可以被对照检查的全部东西：该 Review 自己的需求上下文、
     * 它所针对的那个修订的验收条件、召回白名单，
     * 以及本次调用实际被展示过的那些文件。
     *
     * <p>这一切都来自该 Review 的不可变快照，而不是 PR 的当前状态，
     * 因此重新校验一次历史回答不会改变它的含义（3.5）。
     */
    public record Context(Long requirementId, Long requirementRevisionId, String requirementText,
            List<Ac> acceptanceCriteria, Map<Long, String> knowledgeExcerptHashes,
            List<ChangedFile> visibleFiles) {

        public Context {
            if ((requirementId == null) != (requirementRevisionId == null)) {
                // 与 ck_review_requirement_pairing 强制的是同一个配对关系：
                // 只设一半，正是复合外键会跳过自身检查的原因。
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

        /** {@code key} 就是 {@code ac_key}：与 {@link #id()} 不同，它跨修订稳定。 */
        public record Ac(long id, String key, String text) {
        }

        /** 同一份上下文，收窄到单个批次的文件。其余一切都属于该 Review，而非该批次。 */
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

        /** 大小写敏感：{@code Api.java} 与 {@code api.java} 是两个文件（3.4）。 */
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
     * 对一次回答的裁定，用执行状态机自己的词汇表达
     * （3.2：输出通过校验则 {@code RUNNING -> COMPLETED}，
     * 修复过的回答仍然非法则 {@code RUNNING -> FAILED}）。
     *
     * <p>正是在这个构造器里，「绝不产出成功的空报告」从一条规则变成了一个事实：
     * FAILED 的 outcome 无法携带 output，而 COMPLETED 的 outcome 没有它就无法存在。
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

    /** 单个批次的贡献：候选项与 AC 证据，不含任何裁定（D002）。 */
    record BatchAnswer(List<FindingCandidate> candidates, List<AcEvidence> evidence, List<String> warnings) {
    }

    /** 「某条验收条件在 diff 的某处被涉及了」的证据。下结论的是综合阶段，不是批次。 */
    public record AcEvidence(long acId, String acKey, String path, Integer line, String excerpt) {
    }

    /** 结构上无法使用的回答。不携带任何模型文本：因为它会进入运维可见的输出。 */
    static final class MalformedAnswer extends RuntimeException {

        MalformedAnswer(String message) {
            super(message);
        }
    }
}
