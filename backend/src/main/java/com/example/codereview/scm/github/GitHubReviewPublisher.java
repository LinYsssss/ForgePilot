package com.example.codereview.scm.github;

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

    public GitHubReviewPublisher(ObjectMapper mapper, @Value("${app.scm.allow-insecure-localhost:false}") boolean local) {
        this.http = new ScmHttpSupport(mapper, local);
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
