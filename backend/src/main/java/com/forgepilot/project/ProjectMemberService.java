package com.forgepilot.project;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns membership and role-set mutations; account facts stay behind {@link UserDirectory}. */
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
        Map<Long, AccountView> accounts = users.byIds(rows.stream().map(ProjectMember::getUserId).toList())
                .stream().collect(Collectors.toMap(AccountView::id, account -> account));
        return rows.stream().map(row -> MemberResponse.of(row, accounts.get(row.getUserId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<MemberCandidateResponse> search(long projectId, long actorId, String rawQuery,
            int page, int size) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        String query = rawQuery.trim();
        boolean numericId = !query.isEmpty() && query.chars().allMatch(Character::isDigit);
        if (query.length() < 2 && !numericId) {
            throw ApiException.unprocessable("Search needs at least two characters.");
        }
        if (page < 0 || size < 1 || size > 20) {
            throw ApiException.unprocessable("Invalid candidate page.");
        }
        Set<Long> memberIds = members.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(ProjectMember::getUserId).collect(Collectors.toSet());
        return users.search(query, page, size).stream()
                .map(account -> MemberCandidateResponse.of(account, memberIds.contains(account.id())))
                .toList();
    }

    @Transactional
    public List<MemberResponse> addBatch(long projectId, long actorId, List<BatchMember> requested) {
        projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        access.requireRole(projectId, actorId, ProjectRole.LEADER);

        Set<Long> duplicateCheck = new HashSet<>();
        Map<Long, AccountView> accounts = users.byIds(requested.stream().map(BatchMember::userId).toList())
                .stream().collect(Collectors.toMap(AccountView::id, account -> account));
        Set<Long> existing = members.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(ProjectMember::getUserId).collect(Collectors.toSet());

        for (int index = 0; index < requested.size(); index++) {
            BatchMember row = requested.get(index);
            AccountView account = accounts.get(row.userId());
            if (!duplicateCheck.add(row.userId())) {
                throw rowError(index, "is duplicated.");
            }
            if (account == null || !account.enabled()) {
                throw rowError(index, "does not name an enabled account.");
            }
            if (existing.contains(row.userId())) {
                throw rowError(index, "is already a project member.");
            }
            validateAssignableRoles(index, row.roles());
        }

        return requested.stream().map(row -> {
            ProjectMember member = members.save(new ProjectMember(projectId, row.userId(), row.roles()));
            return MemberResponse.of(member, accounts.get(row.userId()));
        }).toList();
    }

    @Transactional
    public MemberResponse updateRoles(long projectId, long actorId, long targetUserId,
            Set<ProjectRole> requestedRoles) {
        projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        ProjectMember target = members.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(ApiException::notFound);
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            throw ApiException.unprocessable("A project member needs at least one role.");
        }
        if (requestedRoles.contains(ProjectRole.LEADER) != target.hasRole(ProjectRole.LEADER)) {
            throw ApiException.unprocessable("Use the Leader transfer action to change the project Leader.");
        }
        target.replaceRoles(EnumSet.copyOf(requestedRoles));
        return MemberResponse.of(target, users.byId(targetUserId).orElseThrow(ApiException::notFound));
    }

    @Transactional
    public void transferLeader(long projectId, long actorId, long targetUserId) {
        projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        ProjectMember incumbent = access.requireRole(projectId, actorId, ProjectRole.LEADER);
        ProjectMember target = members.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(ApiException::notFound);
        if (incumbent.getUserId().equals(target.getUserId())) {
            throw ApiException.unprocessable("The target is already the project Leader.");
        }
        if (incumbent.getRoles().size() == 1) {
            incumbent.addRole(ProjectRole.DEVELOPER);
        }
        incumbent.removeRole(ProjectRole.LEADER);
        members.flush();
        target.addRole(ProjectRole.LEADER);
    }

    private static void validateAssignableRoles(int index, Set<ProjectRole> roles) {
        if (roles == null || roles.isEmpty() || roles.contains(ProjectRole.LEADER)) {
            throw rowError(index, "needs Developer and/or Reviewer roles; Leader is transferred separately.");
        }
    }

    private static ApiException rowError(int index, String message) {
        return ApiException.unprocessable("Member row " + index + " " + message);
    }

    public record BatchMember(long userId, Set<ProjectRole> roles) {
    }
}
