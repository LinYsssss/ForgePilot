package com.example.codereview.member;

import com.example.codereview.auth.UserAccount;
import com.example.codereview.auth.UserAccountRepository;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.member.ProjectMemberDtos.AddMemberRequest;
import com.example.codereview.member.ProjectMemberDtos.MemberResponse;
import com.example.codereview.member.ProjectMemberDtos.TransferOwnerRequest;
import com.example.codereview.member.ProjectMemberDtos.UpdateMemberRoleRequest;
import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目成员管理(P1a,R1/R6)。角色约束:owner 恒为唯一 LEADER,增改只允许
 * DEVELOPER / REVIEWER,负责人变更只走 {@link #transferOwner}(单事务同步 ownerId 与成员行)。
 */
@Service
public class ProjectMemberService {

    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final UserAccountRepository users;
    private final ProjectAuthorization projectAuthorization;

    public ProjectMemberService(ProjectMemberRepository members, ProjectRepository projects,
                                UserAccountRepository users, ProjectAuthorization projectAuthorization) {
        this.members = members;
        this.projects = projects;
        this.users = users;
        this.projectAuthorization = projectAuthorization;
    }

    public List<MemberResponse> list(Long projectId, Long userId) {
        projectAuthorization.requireRead(projectId, userId);
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        List<ProjectMemberEntity> rows = members.findByProjectIdOrderByCreatedAtAsc(projectId);
        Map<Long, UserAccount> accounts = users.findAllById(rows.stream().map(ProjectMemberEntity::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return rows.stream()
                .map(row -> {
                    UserAccount account = accounts.get(row.getUserId());
                    return new MemberResponse(
                            row.getUserId(),
                            account == null ? null : account.getUsername(),
                            account == null ? null : account.getNickname(),
                            row.getRole().name(),
                            row.getUserId().equals(project.getOwnerId()),
                            row.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    public MemberResponse add(Long projectId, Long operatorId, AddMemberRequest request) {
        projectAuthorization.requireWrite(projectId, operatorId);
        ProjectRole role = requireAssignableRole(request.role());
        UserAccount account = users.findByUsername(request.username().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (members.existsByProjectIdAndUserId(projectId, account.getId())) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_EXISTS);
        }
        ProjectMemberEntity saved = members.save(new ProjectMemberEntity(projectId, account.getId(), role));
        return new MemberResponse(account.getId(), account.getUsername(), account.getNickname(),
                saved.getRole().name(), false, saved.getCreatedAt());
    }

    @Transactional
    public MemberResponse updateRole(Long projectId, Long operatorId, Long memberUserId, UpdateMemberRoleRequest request) {
        projectAuthorization.requireWrite(projectId, operatorId);
        ProjectRole role = requireAssignableRole(request.role());
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        if (memberUserId.equals(project.getOwnerId())) {
            throw new BusinessException(ErrorCode.MEMBER_OWNER_IMMUTABLE);
        }
        ProjectMemberEntity row = members.findByProjectIdAndUserId(projectId, memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        row.changeRole(role);
        UserAccount account = users.findById(memberUserId).orElse(null);
        return new MemberResponse(memberUserId,
                account == null ? null : account.getUsername(),
                account == null ? null : account.getNickname(),
                role.name(), false, row.getCreatedAt());
    }

    @Transactional
    public void remove(Long projectId, Long operatorId, Long memberUserId) {
        projectAuthorization.requireWrite(projectId, operatorId);
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        if (memberUserId.equals(project.getOwnerId())) {
            throw new BusinessException(ErrorCode.MEMBER_OWNER_IMMUTABLE);
        }
        ProjectMemberEntity row = members.findByProjectIdAndUserId(projectId, memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        members.delete(row);
    }

    /** 负责人移交:仅现任 owner 可发起;目标必须已是成员;原负责人降为 DEVELOPER。 */
    @Transactional
    public void transferOwner(Long projectId, Long operatorId, TransferOwnerRequest request) {
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        projectAuthorization.requireRead(projectId, operatorId);
        if (!operatorId.equals(project.getOwnerId())) {
            throw new BusinessException(ErrorCode.PROJECT_FORBIDDEN);
        }
        Long newOwnerId = request.userId();
        if (newOwnerId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.MEMBER_OWNER_IMMUTABLE);
        }
        ProjectMemberEntity newOwnerRow = members.findByProjectIdAndUserId(projectId, newOwnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        project.transferOwner(newOwnerId);
        newOwnerRow.changeRole(ProjectRole.LEADER);
        // 原负责人的成员行可能缺失(存量项目 V29 回填前创建的行为兜底),补一行再降级。
        ProjectMemberEntity previousOwnerRow = members.findByProjectIdAndUserId(projectId, operatorId)
                .orElseGet(() -> members.save(new ProjectMemberEntity(projectId, operatorId, ProjectRole.DEVELOPER)));
        previousOwnerRow.changeRole(ProjectRole.DEVELOPER);
    }

    private ProjectRole requireAssignableRole(String raw) {
        ProjectRole role = ProjectRole.fromName(raw == null ? null : raw.trim());
        if (role == null || role == ProjectRole.LEADER) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色只能是 DEVELOPER 或 REVIEWER");
        }
        return role;
    }
}
