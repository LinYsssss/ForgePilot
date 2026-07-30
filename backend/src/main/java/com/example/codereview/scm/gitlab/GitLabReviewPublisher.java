package com.example.codereview.scm.gitlab;

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
public final class GitLabReviewPublisher implements ScmReviewPublisher {

    private final ScmHttpSupport http;
    private final SecurityAuditLogger audit;

    public GitLabReviewPublisher(ObjectMapper mapper, @Value("${app.scm.allow-insecure-localhost:false}") boolean local,
                                  SecurityAuditLogger audit) {
        this.http = new ScmHttpSupport(mapper, local);
        this.audit = audit;
    }

    @Override
    public ScmProviderType type() {
        return ScmProviderType.GITLAB;
    }

    @Override
    public ScmPublicationResult publish(ScmPublicationContext context, ReviewPublication publication) {
        ReviewPublicationRenderer.requireApproval(context, publication);
        String project = ScmHttpSupport.encodePathSegment(context.repositoryFullName());
        String markdown = ReviewPublicationRenderer.markdown(publication);
        int status = http.postJson(
                context.apiBaseUrl(), "/projects/" + project + "/statuses/" + context.headSha(),
                "PRIVATE-TOKEN", context.credential(),
                Map.of(
                        "state", state(publication.conclusion()),
                        "name", "reposage/pr-gatekeeper",
                        "target_url", publication.agentRunUrl(),
                        "description", publication.summary()));
        int note = http.postJson(
                context.apiBaseUrl(), "/projects/" + project + "/merge_requests/"
                        + context.pullRequestNumber() + "/notes",
                "PRIVATE-TOKEN", context.credential(), Map.of("body", markdown));
        boolean success = status / 100 == 2 && note / 100 == 2;
        // 回写是带凭据、对外可见的动作,成败都要留痕;只记目标与状态码,不记正文。
        audit.recordFor(null, "scm-publisher", "SCM_PUBLISH", success ? Outcome.SUCCESS : Outcome.FAILURE,
                "mergeRequest", context.repositoryFullName() + "!" + context.pullRequestNumber(),
                success ? null : "HTTP_" + status + "_" + note);
        return new ScmPublicationResult(success, List.of(status, note), success ? "published" : "provider rejected publication");
    }

    private static String state(ReviewPublication.Conclusion value) {
        return switch (value) {
            case SUCCESS -> "success";
            case ACTION_REQUIRED -> "failed";
            case NEUTRAL -> "pending";
        };
    }
}
