package com.example.codereview.scm.gitlab;

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

    public GitLabReviewPublisher(ObjectMapper mapper, @Value("${app.scm.allow-insecure-localhost:false}") boolean local) {
        this.http = new ScmHttpSupport(mapper, local);
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
