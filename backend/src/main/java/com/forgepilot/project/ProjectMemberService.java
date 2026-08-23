package com.forgepilot.project;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成员关系与项目内的 SCM 身份。用户名来自 {@link UserDirectory}；
 * 本功能模块绝不注入 {@code UserAccountRepository}（D013.6）。
 */
@Service
public class ProjectMemberService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectAccessService access;
    private final UserDirectory users;

    ProjectMemberService(ProjectRepository projects, ProjectMemberRepository members,
            ProjectAccessService access, UserDirectory users) {
        this.projects = projects;
        this.members = members;
        this.access = access;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(long projectId, long actorId) {
        access.requireMember(projectId, actorId);
        List<ProjectMember> rows = members.findByProjectIdOrderByIdAsc(projectId);
        Map<Long, String> usernames = users.byIds(rows.stream().map(ProjectMember::getUserId).toList())
                .stream().collect(Collectors.toMap(AccountView::id, AccountView::username));
        return rows.stream().map(row -> MemberResponse.of(row, usernames.get(row.getUserId()))).toList();
    }

    @Transactional
    public MemberResponse add(long projectId, long actorId, String username, ProjectRole role) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        // 账号必须按名字解析，没有任何数据库约束能替我们做这件事。
        // 除此之外的一切都交给约束去保证。
        AccountView account = users.byUsername(username)
                .orElseThrow(() -> ApiException.unprocessable("No account with that username."));
        ProjectMember member = members.save(new ProjectMember(projectId, account.id(), role));
        return MemberResponse.of(member, account.username());
    }

    @Transactional
    public MemberResponse update(long projectId, long actorId, long targetUserId, ProjectRole newRole,
            String scmExternalUserId, String scmUsername) {
        // 必须在检查操作者角色**之前**锁住项目行。两个并发的转移必须在此串行化
        // （D013.8），失败的一方随后要重新读自己的角色：赢家提交之后，
        // 操作者已不再是 LEADER，于是第二次转移会被拒绝，而不是基于过期读继续执行。
        projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        access.requireRole(projectId, actorId, ProjectRole.LEADER);

        ProjectMember target = members.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(ApiException::notFound);

        if (newRole != null && newRole != target.getRole()) {
            applyRoleChange(projectId, target, newRole);
        }
        if (scmExternalUserId != null || scmUsername != null) {
            if (scmExternalUserId == null || scmUsername == null) {
                throw ApiException.unprocessable(
                        "An SCM identity needs both scmExternalUserId and scmUsername.");
            }
            target.assignScmIdentity(scmExternalUserId, scmUsername, Instant.now());
        }

        AccountView account = users.byId(targetUserId).orElseThrow(ApiException::notFound);
        return MemberResponse.of(target, account.username());
    }

    private void applyRoleChange(long projectId, ProjectMember target, ProjectRole newRole) {
        if (newRole == ProjectRole.LEADER) {
            ProjectMember incumbent = members.findByProjectIdAndRole(projectId, ProjectRole.LEADER)
                    .orElseThrow(() -> ApiException.conflict("This project has no LEADER to transfer from."));
            incumbent.changeRole(ProjectRole.DEVELOPER);
            // 降级必须先于升级到达数据库。单条 CASE 交换在物理上依赖扫描顺序，
            // 会随机以 23505 失败（D013.8），因此这次 flush 是承重的，不是顺手整理。
            members.flush();
            target.changeRole(ProjectRole.LEADER);
        } else if (target.getRole() == ProjectRole.LEADER) {
            // 没有任何即时约束能表达“至少有一个 LEADER”；这里是那条
            // 逐次提交不变式的服务层一半（D013.9）。
            throw ApiException.unprocessable(
                    "A project must keep a LEADER. Promote another member instead of demoting this one.");
        } else {
            target.changeRole(newRole);
        }
    }
}
