package com.example.codereview.scm;

public final class ReviewPublicationRenderer {

    private ReviewPublicationRenderer() {
    }

    public static String markdown(ReviewPublication publication) {
        StringBuilder text = new StringBuilder();
        text.append("## RepoSage PR Gatekeeper\n\n").append(publication.summary()).append("\n\n");
        text.append("**Conclusion:** ").append(publication.conclusion()).append("\n\n");
        text.append("**Patch validation:** ").append(publication.patchValidationState()).append("\n\n");
        if (!publication.blockingFindings().isEmpty()) {
            text.append("### Blocking findings\n");
            publication.blockingFindings().forEach(item -> text.append("- ").append(item).append('\n'));
            text.append('\n');
        }
        if (!publication.evidenceLinks().isEmpty()) {
            text.append("### Evidence\n");
            publication.evidenceLinks().forEach(item -> text.append("- ").append(item).append('\n'));
            text.append('\n');
        }
        text.append("[Open Agent Run](").append(publication.agentRunUrl()).append(")");
        return text.toString();
    }

    public static void requireApproval(ScmPublicationContext context, ReviewPublication publication) {
        if (publication.exposesPatchContent() && !context.patchContentApproved()) {
            throw new SecurityException("publication exposing patch content requires approval");
        }
    }
}
