package com.example.codereview.project;

import com.example.codereview.ai.AiCallLogRepository;
import com.example.codereview.feedback.FeedbackRepository;
import com.example.codereview.knowledge.KnowledgeChunkRepository;
import com.example.codereview.knowledge.KnowledgeDocumentRepository;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.example.codereview.pullrequest.ReviewActionRepository;
import com.example.codereview.git.GitCliService;
import com.example.codereview.member.ProjectMemberRepository;
import com.example.codereview.mq.MqTaskLogRepository;
import com.example.codereview.rag.VectorIndexService;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.review.ReviewTask;
import com.example.codereview.review.ReviewTaskRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCleanupService {

    private final CodeRepositoryJpaRepository repositories;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final VectorIndexService vectorIndexService;
    private final ReviewTaskRepository tasks;
    private final ReviewReportRepository reports;
    private final ReviewIssueRepository issues;
    private final FeedbackRepository feedback;
    private final ReviewActionRepository reviewActions;
    private final PullRequestRepository pullRequests;
    private final AiCallLogRepository aiCallLogs;
    private final MqTaskLogRepository mqTaskLogs;
    private final GitCliService gitCliService;
    private final ProjectMemberRepository projectMembers;

    public ProjectCleanupService(CodeRepositoryJpaRepository repositories, KnowledgeDocumentRepository documents,
                                 KnowledgeChunkRepository chunks, VectorIndexService vectorIndexService,
                                 ReviewTaskRepository tasks, ReviewReportRepository reports,
                                 ReviewIssueRepository issues, FeedbackRepository feedback,
                                 ReviewActionRepository reviewActions, PullRequestRepository pullRequests,
                                 AiCallLogRepository aiCallLogs, MqTaskLogRepository mqTaskLogs,
                                 GitCliService gitCliService, ProjectMemberRepository projectMembers) {
        this.repositories = repositories;
        this.documents = documents;
        this.chunks = chunks;
        this.vectorIndexService = vectorIndexService;
        this.tasks = tasks;
        this.reports = reports;
        this.issues = issues;
        this.feedback = feedback;
        this.reviewActions = reviewActions;
        this.pullRequests = pullRequests;
        this.aiCallLogs = aiCallLogs;
        this.mqTaskLogs = mqTaskLogs;
        this.gitCliService = gitCliService;
        this.projectMembers = projectMembers;
    }

    @Transactional
    public void purgeProjectData(Long projectId) {
        List<Long> taskIds = tasks.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(ReviewTask::getId)
                .toList();
        List<ReviewReport> projectReports = reports.findByProjectIdOrderByCreatedAtDesc(projectId);
        if (!projectReports.isEmpty()) {
            List<Long> reportIds = projectReports.stream().map(ReviewReport::getId).toList();
            List<Long> issueIds = issues.findByReportIdIn(reportIds).stream().map(ReviewIssue::getId).toList();
            if (!issueIds.isEmpty()) {
                feedback.deleteByIssueIdIn(issueIds);
            }
            issues.deleteByReportIdIn(reportIds);
        }
        reports.deleteByProjectId(projectId);
        if (!taskIds.isEmpty()) {
            mqTaskLogs.deleteByTaskIdIn(taskIds);
        }
        tasks.deleteByProjectId(projectId);
        reviewActions.deleteByProjectId(projectId);
        pullRequests.deleteByProjectId(projectId);

        documents.findByProjectIdOrderByCreatedAtDesc(projectId)
                .forEach(document -> vectorIndexService.deleteByDocumentId(document.getId()));
        chunks.deleteByProjectId(projectId);
        documents.deleteByProjectId(projectId);

        aiCallLogs.deleteByProjectId(projectId);
        // H2 dev 由 ddl-auto 建表、没有 FK 级联,成员行必须应用层删;PG 的级联只是双保险。
        projectMembers.deleteByProjectId(projectId);
        repositories.findByProjectId(projectId).ifPresent(repository -> {
            gitCliService.deleteWorkingCopy(repository.getId());
            repositories.deleteById(repository.getId());
        });
    }
}
