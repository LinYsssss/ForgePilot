package com.example.codereview.project;

import com.example.codereview.ai.AiCallLogRepository;
import com.example.codereview.feedback.FeedbackRepository;
import com.example.codereview.knowledge.KnowledgeChunkRepository;
import com.example.codereview.knowledge.KnowledgeDocumentRepository;
import com.example.codereview.rag.VectorIndexService;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
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
    private final AiCallLogRepository aiCallLogs;

    public ProjectCleanupService(CodeRepositoryJpaRepository repositories, KnowledgeDocumentRepository documents,
                                 KnowledgeChunkRepository chunks, VectorIndexService vectorIndexService,
                                 ReviewTaskRepository tasks, ReviewReportRepository reports,
                                 ReviewIssueRepository issues, FeedbackRepository feedback,
                                 AiCallLogRepository aiCallLogs) {
        this.repositories = repositories;
        this.documents = documents;
        this.chunks = chunks;
        this.vectorIndexService = vectorIndexService;
        this.tasks = tasks;
        this.reports = reports;
        this.issues = issues;
        this.feedback = feedback;
        this.aiCallLogs = aiCallLogs;
    }

    @Transactional
    public void purgeProjectData(Long projectId) {
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
        tasks.deleteByProjectId(projectId);

        documents.findByProjectIdOrderByCreatedAtDesc(projectId)
                .forEach(document -> vectorIndexService.deleteByDocumentId(document.getId()));
        chunks.deleteByProjectId(projectId);
        documents.deleteByProjectId(projectId);

        aiCallLogs.deleteByProjectId(projectId);
        repositories.findByProjectId(projectId).map(CodeRepositoryEntity::getId)
                .ifPresent(id -> repositories.deleteById(id));
    }
}
