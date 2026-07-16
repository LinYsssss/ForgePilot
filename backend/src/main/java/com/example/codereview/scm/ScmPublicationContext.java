package com.example.codereview.scm;

public record ScmPublicationContext(
        String apiBaseUrl,
        String credential,
        String repositoryFullName,
        int pullRequestNumber,
        String headSha,
        boolean patchContentApproved) {

    public ScmPublicationContext {
        requireText(apiBaseUrl, "apiBaseUrl");
        requireText(credential, "credential");
        requireText(repositoryFullName, "repositoryFullName");
        requireText(headSha, "headSha");
        if (pullRequestNumber <= 0) {
            throw new IllegalArgumentException("pullRequestNumber must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
