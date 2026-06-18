package com.example.codereview.pullrequest;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "review_action")
public class ReviewAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long pullRequestId;

    private Long reportId;

    @Column(nullable = false)
    private Long actorId;

    @Column(nullable = false, length = 32)
    private String actionType;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(columnDefinition = "text")
    private String requirementText;

    @Column(columnDefinition = "text")
    private String selectedIssueIds;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReviewAction() {
    }

    public ReviewAction(Long projectId, Long pullRequestId, Long reportId, Long actorId,
                        String actionType, String reason, String requirementText, List<Long> selectedIssueIds) {
        this.projectId = projectId;
        this.pullRequestId = pullRequestId;
        this.reportId = reportId;
        this.actorId = actorId;
        this.actionType = actionType;
        this.reason = blankToNull(reason);
        this.requirementText = blankToNull(requirementText);
        this.selectedIssueIds = serializeIssueIds(selectedIssueIds);
        this.createdAt = Instant.now();
    }

    private static String serializeIssueIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getPullRequestId() {
        return pullRequestId;
    }

    public Long getReportId() {
        return reportId;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getReason() {
        return reason;
    }

    public String getRequirementText() {
        return requirementText;
    }

    public List<Long> getSelectedIssueIds() {
        if (selectedIssueIds == null || selectedIssueIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(selectedIssueIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
