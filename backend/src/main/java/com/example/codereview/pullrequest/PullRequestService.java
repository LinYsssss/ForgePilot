package com.example.codereview.pullrequest;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.git.GitInputValidator;
import com.example.codereview.project.ProjectService;
import com.example.codereview.pullrequest.PullRequestDtos.CreatePullRequestRequest;
import com.example.codereview.pullrequest.PullRequestDtos.PullRequestResponse;
import com.example.codereview.pullrequest.PullRequestDtos.ReviewActionRequest;
import com.example.codereview.pullrequest.PullRequestDtos.ReviewActionResponse;
import com.example.codereview.pullrequest.PullRequestDtos.UpdatePullRequestRequest;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.RepositoryService;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PullRequestService {

    private static final Set<String> ALLOWED_ACTIONS = Set.of("APPROVE", "REQUEST_CHANGES", "WAIVE", "COMMENT");
    private static final Set<String> ALLOWED_STATUSES = Set.of("OPEN", "CLOSED", "MERGED");

    private final ProjectService projectService;
    private final RepositoryService repositoryService;
    private final PullRequestRepository pullRequests;
    private final ReviewActionRepository reviewActions;
    private final ReviewReportRepository reports;
    private final ReviewIssueRepository issues;

    public PullRequestService(ProjectService projectService, RepositoryService repositoryService,
                              PullRequestRepository pullRequests, ReviewActionRepository reviewActions,
                              ReviewReportRepository reports, ReviewIssueRepository issues) {
        this.projectService = projectService;
        this.repositoryService = repositoryService;
        this.pullRequests = pullRequests;
        this.reviewActions = reviewActions;
        this.reports = reports;
        this.issues = issues;
    }

    @Transactional
    public PullRequestResponse create(Long projectId, Long userId, CreatePullRequestRequest request) {
        CodeRepositoryEntity repository = repositoryService.getRequired(projectId, userId);
        validateRefs(request.sourceBranch(), request.targetBranch(), request.baseSha(), request.headSha());
        PullRequestEntity entity = new PullRequestEntity(
                projectId,
                repository.getId(),
                request.provider(),
                request.externalPrId(),
                request.prNumber(),
                request.title(),
                request.authorName(),
                request.sourceBranch(),
                request.targetBranch(),
                request.baseSha(),
                request.headSha()
        );
        return PullRequestResponse.from(pullRequests.save(entity));
    }

    public List<PullRequestResponse> list(Long projectId, Long userId) {
        projectService.getRequired(projectId, userId);
        return pullRequests.findByProjectIdOrderByUpdatedAtDesc(projectId)
                .stream()
                .map(PullRequestResponse::from)
                .toList();
    }

    public PullRequestResponse detail(Long projectId, Long userId, Long pullRequestId) {
        return PullRequestResponse.from(requirePullRequest(projectId, userId, pullRequestId));
    }

    @Transactional
    public PullRequestResponse update(Long projectId, Long userId, Long pullRequestId, UpdatePullRequestRequest request) {
        PullRequestEntity pullRequest = requirePullRequest(projectId, userId, pullRequestId);
        validateRefs(request.sourceBranch(), request.targetBranch(), request.baseSha(), request.headSha());
        String status = request.status() == null || request.status().isBlank() ? pullRequest.getStatus() : request.status();
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new BusinessException(400, "PR 状态不合法");
        }
        pullRequest.update(
                request.prNumber(),
                request.title(),
                request.authorName(),
                request.sourceBranch(),
                request.targetBranch(),
                request.baseSha(),
                request.headSha(),
                request.provider(),
                request.externalPrId(),
                status
        );
        return PullRequestResponse.from(pullRequest);
    }

    @Transactional
    public ReviewActionResponse createAction(Long projectId, Long userId, Long pullRequestId, ReviewActionRequest request) {
        PullRequestEntity pullRequest = requirePullRequest(projectId, userId, pullRequestId);
        String actionType = request.actionType();
        if (!ALLOWED_ACTIONS.contains(actionType)) {
            throw new BusinessException(400, "审核动作不合法");
        }
        if ("REQUEST_CHANGES".equals(actionType) && isBlank(request.reason()) && isBlank(request.requirement())) {
            throw new BusinessException(400, "打回原因或整改要求不能为空");
        }
        validateReportAndIssues(projectId, request.reportId(), request.selectedIssueIds());
        ReviewAction action = new ReviewAction(
                projectId,
                pullRequest.getId(),
                request.reportId(),
                userId,
                actionType,
                request.reason(),
                request.requirement(),
                request.selectedIssueIds()
        );
        reviewActions.save(action);
        pullRequest.applyReviewState(actionType);
        return ReviewActionResponse.from(action);
    }

    public List<ReviewActionResponse> actions(Long projectId, Long userId, Long pullRequestId) {
        PullRequestEntity pullRequest = requirePullRequest(projectId, userId, pullRequestId);
        return reviewActions.findByPullRequestIdOrderByCreatedAtDesc(pullRequest.getId())
                .stream()
                .map(ReviewActionResponse::from)
                .toList();
    }

    public PullRequestEntity requirePullRequest(Long projectId, Long userId, Long pullRequestId) {
        projectService.getRequired(projectId, userId);
        PullRequestEntity pullRequest = pullRequests.findById(pullRequestId)
                .orElseThrow(() -> new BusinessException(404, "PR 不存在"));
        if (!pullRequest.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "无权访问该 PR");
        }
        return pullRequest;
    }

    private void validateRefs(String sourceBranch, String targetBranch, String baseSha, String headSha) {
        GitInputValidator.requireSafeRef(sourceBranch, "源分支");
        GitInputValidator.requireSafeRef(targetBranch, "目标分支");
        GitInputValidator.requireSafeRef(baseSha, "Base SHA");
        GitInputValidator.requireSafeRef(headSha, "Head SHA");
    }

    private void validateReportAndIssues(Long projectId, Long reportId, List<Long> selectedIssueIds) {
        if (reportId == null) {
            if (selectedIssueIds != null && !selectedIssueIds.isEmpty()) {
                throw new BusinessException(400, "选择问题时必须关联审查报告");
            }
            return;
        }
        ReviewReport report = reports.findById(reportId)
                .orElseThrow(() -> new BusinessException(404, "审查报告不存在"));
        if (!report.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "审查报告不属于当前项目");
        }
        if (selectedIssueIds == null || selectedIssueIds.isEmpty()) {
            return;
        }
        Set<Long> issueIds = new HashSet<>(issues.findByReportId(reportId).stream().map(ReviewIssue::getId).toList());
        boolean allBelongToReport = selectedIssueIds.stream().allMatch(issueIds::contains);
        if (!allBelongToReport) {
            throw new BusinessException(400, "选择的问题不属于关联报告");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
