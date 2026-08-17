package com.example.codereview.requirement;

import com.example.codereview.auth.UserAccount;
import com.example.codereview.auth.UserAccountRepository;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.member.ProjectMemberRepository;
import com.example.codereview.member.ProjectRole;
import com.example.codereview.requirement.RequirementDtos.AcItem;
import com.example.codereview.requirement.RequirementDtos.AcResponse;
import com.example.codereview.requirement.RequirementDtos.AssignRequest;
import com.example.codereview.requirement.RequirementDtos.RequirementDetail;
import com.example.codereview.requirement.RequirementDtos.RequirementSummary;
import com.example.codereview.requirement.RequirementDtos.SaveRequirementRequest;
import com.example.codereview.requirement.RequirementDtos.StatusRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requirement 域(P1b,R2)。角色边界沿 P1a 矩阵:创建/编辑/指派/READY 推进/取消 = LEADER;
 * IN_DEVELOPMENT→IN_REVIEW 额外放行被指派的 DEVELOPER;读 = 任意成员。
 * 状态图见 {@link RequirementStatus};内容锁定与指派守卫在这里。
 */
@Service
public class RequirementService {

    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final ProjectAuthorization projectAuthorization;
    private final ProjectMemberRepository members;
    private final UserAccountRepository users;

    public RequirementService(RequirementRepository requirements, AcceptanceCriterionRepository criteria,
                              ProjectAuthorization projectAuthorization, ProjectMemberRepository members,
                              UserAccountRepository users) {
        this.requirements = requirements;
        this.criteria = criteria;
        this.projectAuthorization = projectAuthorization;
        this.members = members;
        this.users = users;
    }

    @Transactional
    public RequirementDetail create(Long projectId, Long userId, SaveRequirementRequest request) {
        projectAuthorization.requireWrite(projectId, userId);
        String priority = normalizePriority(request.priority());
        RequirementEntity saved = saveWithSeqRetry(projectId, userId, request, priority);
        replaceCriteria(saved.getId(), request.acceptanceCriteria());
        return detailOf(saved);
    }

    /** 取号与保存同一事务:max+1,唯一约束兜底后重取一次(演示规模无热点争用)。 */
    private RequirementEntity saveWithSeqRetry(Long projectId, Long userId,
                                               SaveRequirementRequest request, String priority) {
        try {
            RequirementEntity entity = new RequirementEntity(projectId, requirements.maxSeq(projectId) + 1,
                    request.title(), request.background(), request.description(), priority, userId);
            return requirements.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            RequirementEntity retry = new RequirementEntity(projectId, requirements.maxSeq(projectId) + 1,
                    request.title(), request.background(), request.description(), priority, userId);
            return requirements.saveAndFlush(retry);
        }
    }

    public PageResponse<RequirementSummary> list(Long projectId, Long userId, String status,
                                                 Integer page, Integer size) {
        projectAuthorization.requireRead(projectId, userId);
        PageRequest pageRequest = PageRequest.of(PageResponse.sanitizePage(page), PageResponse.sanitizeSize(size));
        Page<RequirementEntity> result = status == null || status.isBlank()
                ? requirements.findByProjectIdOrderBySeqDesc(projectId, pageRequest)
                : requirements.findByProjectIdAndStatusOrderBySeqDesc(
                        projectId, status.trim().toUpperCase(Locale.ROOT), pageRequest);
        List<Long> ids = result.getContent().stream().map(RequirementEntity::getId).toList();
        Map<Long, Long> acCounts = ids.isEmpty() ? Map.of()
                : criteria.findByRequirementIdInOrderBySeqAsc(ids).stream()
                        .collect(Collectors.groupingBy(AcceptanceCriterionEntity::getRequirementId,
                                Collectors.counting()));
        Map<Long, String> names = displayNames(result.getContent().stream()
                .map(RequirementEntity::getAssigneeId).filter(java.util.Objects::nonNull).toList());
        return PageResponse.from(result, entity -> new RequirementSummary(
                entity.getId(),
                "REQ-" + entity.getSeq(),
                entity.getTitle(),
                entity.getPriority(),
                entity.getStatus().name(),
                entity.getAssigneeId(),
                // Map.of() 的不可变实现对 null 键直接 NPE:未指派的需求必须先短路。
                entity.getAssigneeId() == null ? null : names.get(entity.getAssigneeId()),
                acCounts.getOrDefault(entity.getId(), 0L).intValue(),
                entity.getUpdatedAt()));
    }

    public RequirementDetail detail(Long projectId, Long userId, Long requirementId) {
        projectAuthorization.requireRead(projectId, userId);
        return detailOf(requireRequirement(projectId, requirementId));
    }

    @Transactional
    public RequirementDetail update(Long projectId, Long userId, Long requirementId, SaveRequirementRequest request) {
        projectAuthorization.requireWrite(projectId, userId);
        RequirementEntity entity = requireRequirement(projectId, requirementId);
        if (entity.getStatus().locksContent()) {
            throw new BusinessException(ErrorCode.REQUIREMENT_LOCKED);
        }
        entity.updateContent(request.title(), request.background(), request.description(),
                normalizePriority(request.priority()));
        replaceCriteria(entity.getId(), request.acceptanceCriteria());
        return detailOf(entity);
    }

    @Transactional
    public RequirementDetail assign(Long projectId, Long userId, Long requirementId, AssignRequest request) {
        projectAuthorization.requireWrite(projectId, userId);
        RequirementEntity entity = requireRequirement(projectId, requirementId);
        if (!members.existsByProjectIdAndUserId(projectId, request.userId())) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "指派对象必须是项目成员");
        }
        entity.assign(request.userId());
        return detailOf(entity);
    }

    @Transactional
    public RequirementDetail transition(Long projectId, Long userId, Long requirementId, StatusRequest request) {
        RequirementEntity entity = requireReadable(projectId, userId, requirementId);
        RequirementStatus target = RequirementStatus.fromName(
                request.status() == null ? null : request.status().trim().toUpperCase(Locale.ROOT));
        if (target == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知的需求状态");
        }
        RequirementStatus current = entity.getStatus();
        // 角色:IN_DEVELOPMENT→IN_REVIEW 放行被指派人,其余流转 LEADER 专属。
        boolean assigneeSubmitting = current == RequirementStatus.IN_DEVELOPMENT
                && target == RequirementStatus.IN_REVIEW
                && userId.equals(entity.getAssigneeId());
        if (!assigneeSubmitting) {
            projectAuthorization.requireRole(projectId, userId, Set.of(ProjectRole.LEADER));
        }
        if (!RequirementStatus.canTransition(current, target)) {
            throw new BusinessException(ErrorCode.REQUIREMENT_TRANSITION_ILLEGAL,
                    "不允许 " + current + " → " + target);
        }
        if (target == RequirementStatus.IN_DEVELOPMENT && entity.getAssigneeId() == null) {
            throw new BusinessException(ErrorCode.REQUIREMENT_TRANSITION_ILLEGAL, "进入开发前必须先指派开发人员");
        }
        entity.moveTo(target);
        return detailOf(entity);
    }

    // ------------------------------------------------------------------ helpers

    private RequirementEntity requireReadable(Long projectId, Long userId, Long requirementId) {
        projectAuthorization.requireRead(projectId, userId);
        return requireRequirement(projectId, requirementId);
    }

    private RequirementEntity requireRequirement(Long projectId, Long requirementId) {
        return requirements.findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUIREMENT_NOT_FOUND));
    }

    private void replaceCriteria(Long requirementId, List<AcItem> items) {
        criteria.deleteByRequirementId(requirementId);
        if (items == null) {
            return;
        }
        int seq = 0;
        for (AcItem item : items) {
            if (item.text() == null || item.text().isBlank()) {
                continue;
            }
            seq++;
            criteria.save(new AcceptanceCriterionEntity(requirementId, seq, item.text().trim()));
        }
    }

    private RequirementDetail detailOf(RequirementEntity entity) {
        List<AcResponse> acs = criteria.findByRequirementIdOrderBySeqAsc(entity.getId())
                .stream().map(AcResponse::from).toList();
        String assigneeName = entity.getAssigneeId() == null ? null
                : displayNames(List.of(entity.getAssigneeId())).get(entity.getAssigneeId());
        return new RequirementDetail(
                entity.getId(),
                "REQ-" + entity.getSeq(),
                entity.getTitle(),
                entity.getBackground(),
                entity.getDescription(),
                entity.getPriority(),
                entity.getStatus().name(),
                entity.getAssigneeId(),
                assigneeName,
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                acs);
    }

    private Map<Long, String> displayNames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, RequirementService::displayName, (a, b) -> a));
    }

    private static String displayName(UserAccount account) {
        String nickname = account.getNickname();
        return nickname == null || nickname.isBlank() ? account.getUsername() : nickname;
    }

    private String normalizePriority(String raw) {
        String priority = raw == null || raw.isBlank() ? "MEDIUM" : raw.trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优先级只能是 HIGH / MEDIUM / LOW");
        }
        return priority;
    }
}
