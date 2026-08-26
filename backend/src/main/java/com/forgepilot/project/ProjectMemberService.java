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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns membership and role-set mutations; account facts stay behind {@link UserDirectory}. */
@Service
public class ProjectMemberService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectAccessService access;
    private final UserDirectory users;
    private final ProjectDeletionLog deletions;
    private final ApplicationEventPublisher publisher;

    ProjectMemberService(ProjectRepository projects, ProjectMemberRepository members,
            ProjectAccessService access, UserDirectory users, ProjectDeletionLog deletions,
            ApplicationEventPublisher publisher) {
        this.projects = projects;
        this.members = members;
        this.access = access;
        this.users = users;
        this.deletions = deletions;
        this.publisher = publisher;
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

    /**
     * 把一个成员从项目里移除，并撤销它的一切活权限。
     *
     * <p>顺序是承重的：**先**发事件让各模块撤销自己那部分引用，**再**删成员行。
     * 反过来的话，那三处引用的外键（都没有 {@code ON DELETE}）会让删除直接被
     * 数据库以 23503 拒绝——这同时也意味着漏掉一个监听器不会静默通过，外键本身
     * 就是「每一处引用都真的撤销了」的证明。
     *
     * <p>{@code project_member_role} 不必显式删除：它是
     * {@code @ElementCollection} + {@code @CollectionTable}，随实体一起消失。
     * {@code pull_request.author_user_id} 也不必：全库唯一那条列级
     * {@code ON DELETE SET NULL}当初正是为这个场景设计的，而作为事实的
     * 作者身份由两列不可变快照承载，移除后完整可读。
     *
     * <p>唯一 LEADER 由服务端拒绝，而不是交给约束：
     * {@code UNIQUE(project_id) WHERE role='LEADER'} 保证的是**至多**一个，
     * 「至少一个」历来是服务端职责。删掉唯一 LEADER 不违反任何
     * 约束，只会让项目失去负责人。
     */
    @Transactional
    public void remove(long projectId, long actorId, long targetUserId) {
        // 与角色编辑、负责人转移同一把行锁，因此移除与转移互相串行化。
        projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        ProjectMember target = members.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(ApiException::notFound);
        if (target.hasRole(ProjectRole.LEADER)) {
            throw ApiException.conflict(
                    "The project Leader cannot be removed; transfer the Leader role first.");
        }

        ProjectMemberRemoving removing = new ProjectMemberRemoving(projectId, targetUserId);
        removing.revoked("roles", target.getRoles().size());
        publisher.publishEvent(removing);

        members.delete(target);
        // 让外键在本方法内就说话，而不是等到提交时才炸在调用栈之外。
        members.flush();
        deletions.record(projectId, DeletedResourceType.PROJECT_MEMBER, targetUserId, actorId,
                removing.summary());
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
