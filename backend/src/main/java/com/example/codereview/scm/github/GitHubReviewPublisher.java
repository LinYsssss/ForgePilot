package com.example.codereview.scm.github;

import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.common.security.SecurityAuditLogger.Outcome;
import com.example.codereview.scm.ReviewPublication;
import com.example.codereview.scm.ReviewPublicationRenderer;
import com.example.codereview.scm.ScmHttpSupport;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.ScmPublicationContext;
import com.example.codereview.scm.ScmPublicationResult;
import com.example.codereview.scm.ScmReviewPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class GitHubReviewPublisher implements ScmReviewPublisher {

    private final ScmHttpSupport http;
    private final SecurityAuditLogger audit;

    public GitHubReviewPublisher(ObjectMapper mapper, @Value("${app.scm.allow-insecure-localhost:false}") boolean local,
                                  SecurityAuditLogger audit) {
        this.http = new ScmHttpSupport(mapper, local);
        this.audit = audit;
    }

    @Override
    public ScmProviderType type() {
        return ScmProviderType.GITHUB;
    }

    @Override
    public ScmPublicationResult publish(ScmPublicationContext context, ReviewPublication publication) {
        ReviewPublicationRenderer.requireApproval(context, publication);
        String markdown = ReviewPublicationRenderer.markdown(publication);
        String repository = context.repositoryFullName();
        String auth = "Bearer " + context.credential();
        int check = http.postJson(
                context.apiBaseUrl(), "/repos/" + repository + "/check-runs", "Authorization", auth,
                Map.of(
                        "name", "RepoSage PR Gatekeeper",
                        "head_sha", context.headSha(),
                        "status", "completed",
                        "conclusion", conclusion(publication.conclusion()),
                        "details_url", publication.agentRunUrl(),
                        "output", Map.of("title", "RepoSage review", "summary", markdown)));
        int comment = http.postJson(
                context.apiBaseUrl(), "/repos/" + repository + "/issues/" + context.pullRequestNumber() + "/comments",
                "Authorization", auth, Map.of("body", markdown));
        boolean success = check / 100 == 2 && comment / 100 == 2;
        // 回写是带凭据、对外可见的动作,成败都要留痕;只记目标与状态码,不记正文。
        audit.recordFor(null, "scm-publisher", "SCM_PUBLISH", success ? Outcome.SUCCESS : Outcome.FAILURE,
                "pullRequest", repository + "#" + context.pullRequestNumber(),
                success ? null : "HTTP_" + check + "_" + comment);
        return new ScmPublicationResult(success, List.of(check, comment), success ? "published" : "provider rejected publication");
    }

    private static String conclusion(ReviewPublication.Conclusion value) {
        return switch (value) {
            case SUCCESS -> "success";
            case ACTION_REQUIRED -> "action_required";
            case NEUTRAL -> "neutral";
        };
    }
}
