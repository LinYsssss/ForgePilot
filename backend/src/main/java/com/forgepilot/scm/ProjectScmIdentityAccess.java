package com.forgepilot.scm;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Live “my PR” identity check shared with review without exposing SCM repositories. */
@Service
public class ProjectScmIdentityAccess {
    private final PullRequestAuthorMapper authors;

    ProjectScmIdentityAccess(PullRequestAuthorMapper authors) {
        this.authors = authors;
    }

    @Transactional(readOnly = true)
    public boolean isActiveAuthor(long projectId, long userId, String externalUserId) {
        Long mapped = authors.userIdFor(projectId, externalUserId);
        return mapped != null && mapped == userId;
    }
}
