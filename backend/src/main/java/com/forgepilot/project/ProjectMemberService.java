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
 * Membership and the project-scoped SCM identity. Usernames come from
 * {@link UserDirectory}; this feature never injects {@code UserAccountRepository}
 * (D013.6).
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
        // The account has to be resolved by name; no database constraint can do
        // that for us. Everything else here is left to the constraints.
        AccountView account = users.byUsername(username)
                .orElseThrow(() -> ApiException.unprocessable("No account with that username."));
        ProjectMember member = members.save(new ProjectMember(projectId, account.id(), role));
        return MemberResponse.of(member, account.username());
    }

    @Transactional
    public MemberResponse update(long projectId, long actorId, long targetUserId, ProjectRole newRole,
            String scmExternalUserId, String scmUsername) {
        // Lock the project row *before* checking the actor's role. Two concurrent
        // transfers must serialise here (D013.8), and the loser must then re-read
        // its own role: after the winner commits, the actor is no longer LEADER
        // and the second transfer is rejected instead of acting on a stale read.
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
            // The demote must reach the database before the promote. A single
            // CASE swap is physically scan-order dependent and fails at random
            // with 23505 (D013.8), so this flush is load-bearing, not a tidy-up.
            members.flush();
            target.changeRole(ProjectRole.LEADER);
        } else if (target.getRole() == ProjectRole.LEADER) {
            // No immediate constraint can express "at least one LEADER"; this is
            // the service half of that per-commit invariant (D013.9).
            throw ApiException.unprocessable(
                    "A project must keep a LEADER. Promote another member instead of demoting this one.");
        } else {
            target.changeRole(newRole);
        }
    }
}
