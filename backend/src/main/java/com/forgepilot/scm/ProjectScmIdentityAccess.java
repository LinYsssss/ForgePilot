package com.forgepilot.scm;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供 {@code review} 使用的「本人 PR」实时判定：按 Provider + 实例 + 稳定外部用户 ID 与
 * 成员当前活动绑定比对（PRD P11），不暴露任何 SCM 仓库类型，也不按用户名授权。
 */
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
