package com.example.codereview.finding;

import com.example.codereview.agent.api.AgentRunService;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.common.security.SecurityAuditLogger.Outcome;
import com.example.codereview.finding.ReviewFeedbackDtos.FeedbackRecord;
import com.example.codereview.finding.ReviewFeedbackDtos.FeedbackRequest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 反馈闭环(生产加固 R3)的写入与导出:人工裁定(误报/确认/漏报)落库,
 * 作为 r8 提示词回灌流程的输入工件——导出只读,不自动写入任何评测语料,回灌准入是人工策展。
 *
 * <p>访问控制直接复用 {@link AgentRunService#requireOwnedRun}(run 不存在 404,
 * run 所属项目不属于当前用户则由项目守卫给 403/404),不做第二份实现。导出端点由
 * SecurityConfig 按 ADMIN 角色整体把关,服务层不重复判角色(与 ScmInstallationAdminService 同模式)。
 *
 * <p>幂等:同 reporter+finding+type 重复提交覆盖旧内容,不 409;MISS_REPORT 无 finding 可挂,
 * 幂等键退化为 run+reporter+path+line,重复补录同一位置同样覆盖而非堆重复行。
 */
@Service
public class ReviewFeedbackService {

    private final ReviewFeedbackRepository feedbacks;
    private final AgentRunService agentRuns;
    private final FindingRepository findings;
    private final SecurityAuditLogger audit;

    public ReviewFeedbackService(ReviewFeedbackRepository feedbacks,
                                 AgentRunService agentRuns,
                                 FindingRepository findings,
                                 SecurityAuditLogger audit) {
        this.feedbacks = feedbacks;
        this.agentRuns = agentRuns;
        this.findings = findings;
        this.audit = audit;
    }

    @Transactional
    public FeedbackRecord submit(Long runId, Long reporterId, FeedbackRequest request) {
        agentRuns.requireOwnedRun(runId, reporterId);
        String path = normalizePath(request.path());
        validateCombination(runId, path, request);
        ReviewFeedback saved = feedbacks.save(upsert(runId, reporterId, path, request));
        // 反馈直接影响 r8 语料策展,「谁在何时裁定过什么」必须可回放;
        // reason 只放稳定的类型标识,不放用户自由文本(审计行禁止不可控输入)。
        audit.record("REVIEW_FEEDBACK_SUBMIT", Outcome.SUCCESS,
                "reviewFeedback", String.valueOf(saved.getId()), request.type().name());
        return FeedbackRecord.from(saved);
    }

    /** 导出给 r8 人工策展;since 为空即全量。行序按 id 升序,多次导出可稳定 diff。 */
    @Transactional(readOnly = true)
    public List<FeedbackRecord> export(Instant since) {
        List<ReviewFeedback> rows = since == null
                ? feedbacks.findAllByOrderByIdAsc()
                : feedbacks.findByCreatedAtGreaterThanEqualOrderByIdAsc(since);
        return rows.stream().map(FeedbackRecord::from).toList();
    }

    /**
     * type 与字段的组合校验(校验矩阵的 400 分支)。挂靠型反馈还要求 finding 真实属于本次
     * run——跨 run 的 finding id 一律按不存在处理(404),不泄露其它项目资源的存在性。
     */
    private void validateCombination(Long runId, String path, FeedbackRequest request) {
        if (request.type().requiresFinding()) {
            if (request.findingId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "误报/确认反馈必须携带 findingId");
            }
            Finding finding = findings.findById(request.findingId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.FINDING_NOT_FOUND, "问题记录不存在"));
            if (!runId.equals(finding.getAgentRunId())) {
                throw new BusinessException(ErrorCode.FINDING_NOT_FOUND, "问题记录不存在");
            }
            return;
        }
        if (request.findingId() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "漏报反馈不能挂靠 findingId");
        }
        if (path == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "漏报反馈必须携带 path 定位遗漏位置");
        }
    }

    /** 命中幂等键则整体覆盖旧内容(取最新),否则新建;两种形态的键不同,见类头注释。 */
    private ReviewFeedback upsert(Long runId, Long reporterId, String path, FeedbackRequest request) {
        Optional<ReviewFeedback> existing = request.type().requiresFinding()
                ? feedbacks.findByReporterIdAndFindingIdAndType(reporterId, request.findingId(), request.type())
                : findSameMissReport(runId, reporterId, path, request.line());
        if (existing.isPresent()) {
            existing.get().replaceContent(path, request.line(), request.category(), request.note());
            return existing.get();
        }
        return new ReviewFeedback(runId, request.findingId(), request.type(),
                path, request.line(), request.category(), request.note(), reporterId);
    }

    /** path+line 相同即视为同一处漏报;line 可空,派生查询钉不住 null,故在内存匹配。 */
    private Optional<ReviewFeedback> findSameMissReport(Long runId, Long reporterId, String path, Integer line) {
        return feedbacks.findByAgentRunIdAndReporterIdAndType(runId, reporterId, ReviewFeedbackType.MISS_REPORT)
                .stream()
                .filter(row -> Objects.equals(row.getFilePath(), path) && Objects.equals(row.getLineNumber(), line))
                .findFirst();
    }

    /** 首尾空白会让同一个文件路径生成两条"不同"记录,入库前统一归一;空白串视同未提供。 */
    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return path.strip();
    }
}
