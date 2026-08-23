package com.forgepilot.requirement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 需求、它们不可变的修订，以及支配二者的状态机（design.md 6.4）。授权走
 * {@link ProjectAccessService}，用户名走 {@link UserDirectory}：本功能模块
 * 既不注入 {@code ProjectMemberRepository} 也不注入
 * {@code UserAccountRepository}（D013.6）。
 *
 * <p>约束冲突——跨项目的父级、非成员的被指派人、重复的修订 seq——一律交给
 * 数据库处理并让其事务回滚（D013.11）；这里不捕获、也不绕过其中任何一种。
 */
@Service
public class RequirementService {

    private static final String AC_KEY_PREFIX = "AC-";

    /**
     * 把 api-contract 3 的流转表写成数据。{@code IN_DEVELOPMENT} 刻意不出现在
     * 任何取值里：进入它的唯一入口是首次指派，因此 {@link #changeStatus}
     * 永远到不了那个状态。
     */
    private static final Map<RequirementStatus, Set<RequirementStatus>> ALLOWED_TARGETS = Map.of(
            RequirementStatus.DRAFT, Set.of(RequirementStatus.READY, RequirementStatus.CANCELED),
            RequirementStatus.READY, Set.of(RequirementStatus.CANCELED),
            RequirementStatus.IN_DEVELOPMENT, Set.of(RequirementStatus.DONE, RequirementStatus.CANCELED),
            RequirementStatus.DONE, Set.of(),
            RequirementStatus.CANCELED, Set.of());

    private final RequirementRepository requirements;
    private final RequirementRevisionRepository revisions;
    private final AcceptanceCriterionRepository criteria;
    private final ProjectAccessService access;
    private final UserDirectory users;

    RequirementService(RequirementRepository requirements, RequirementRevisionRepository revisions,
            AcceptanceCriterionRepository criteria, ProjectAccessService access, UserDirectory users) {
        this.requirements = requirements;
        this.revisions = revisions;
        this.criteria = criteria;
        this.access = access;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<RequirementSummary> list(long projectId, long actorId) {
        access.requireMember(projectId, actorId);
        List<Requirement> rows = requirements.findByProjectIdOrderByIdAsc(projectId);
        Map<Long, String> usernames = usernames(rows.stream().map(Requirement::getAssigneeId));
        return rows.stream()
                .map(row -> RequirementSummary.of(row, usernames.get(row.getAssigneeId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RequirementDetail get(long projectId, long actorId, long requirementId) {
        access.requireMember(projectId, actorId);
        Requirement requirement = require(projectId, requirementId);
        return detail(requirement, requirement.getCurrentRevision());
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(long projectId, long actorId, long requirementId) {
        access.requireMember(projectId, actorId);
        require(projectId, requirementId);
        List<RequirementRevision> history =
                revisions.findByProjectIdAndRequirementIdOrderBySeqAsc(projectId, requirementId);
        Map<Long, List<AcceptanceCriterion>> byRevision = criteria
                .findByProjectIdAndRequirementRevisionIdInOrderBySortOrderAsc(projectId,
                        history.stream().map(RequirementRevision::getId).toList())
                .stream().collect(Collectors.groupingBy(AcceptanceCriterion::getRequirementRevisionId));
        Map<Long, String> usernames = usernames(history.stream().map(RequirementRevision::getCreatedBy));
        return history.stream()
                .map(revision -> RevisionView.of(revision, usernames.get(revision.getCreatedBy()),
                        byRevision.getOrDefault(revision.getId(), List.of())))
                .toList();
    }

    /**
     * 一个事务里的三步（D013.10）：先插入指针为 null 的需求行，再插入修订 1
     * 及其验收条件，最后回填指针。那个复合外键是 MATCH SIMPLE，
     * 因此指针为 null 期间它被跳过，回填时才被完整检查。
     */
    @Transactional
    public RequirementDetail create(long projectId, long actorId, RequirementContent content) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Requirement requirement = requirements.save(new Requirement(projectId));
        RequirementRevision revision = revisions.save(new RequirementRevision(projectId, requirement.getId(),
                1, content.title(), content.background(), content.description(), actorId, null));
        List<AcceptanceCriterion> written = insertCriteria(revision, content.acceptanceCriteria(), Set.of());
        requirement.setCurrentRevisionId(revision.getId());
        return detail(requirement, revision, written);
    }

    /**
     * 对修订 1 的原地编辑，只有在需求仍为 DRAFT 时才合法。质量结果描述的是
     * 旧文本，因此在同一个事务里被清空（design.md 6.4）。
     */
    @Transactional
    public RequirementDetail editDraft(long projectId, long actorId, long requirementId,
            RequirementContent content) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Requirement requirement = require(projectId, requirementId);
        if (requirement.getStatus() != RequirementStatus.DRAFT) {
            throw ApiException.conflict(
                    "This requirement has left DRAFT; publish a new revision instead of editing in place.");
        }
        RequirementRevision revision = requirement.getCurrentRevision();
        revision.editProse(content.title(), content.background(), content.description());
        return detail(requirement, revision, rewriteCriteria(revision, content.acceptanceCriteria()));
    }

    /**
     * 需求一旦离开 DRAFT，其修订即被冻结，于是变更会带着必填的原因发布
     * {@code seq + 1} 并再次回填指针。当前修订永远是最新的那一个，
     * 这正是要递增它的 seq 的原因；并发的第二次发布会在唯一键
     * {@code (requirement_id, seq)} 上落败。
     */
    @Transactional
    public RequirementDetail publishRevision(long projectId, long actorId, long requirementId,
            RequirementContent content, String changeReason) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Requirement requirement = require(projectId, requirementId);
        if (requirement.getStatus() == RequirementStatus.DRAFT) {
            throw ApiException.conflict("A DRAFT requirement is edited in place, not published as a revision.");
        }
        if (isTerminal(requirement.getStatus())) {
            throw ApiException.conflict("This requirement is in a terminal state and can no longer change.");
        }
        if (changeReason == null || changeReason.isBlank()) {
            throw ApiException.unprocessable("Publishing a revision requires a changeReason.");
        }
        Set<String> usedKeys = new HashSet<>(criteria.findKeysOfRequirement(projectId, requirementId));
        RequirementRevision published = revisions.save(new RequirementRevision(projectId, requirementId,
                requirement.getCurrentRevision().getSeq() + 1, content.title(), content.background(),
                content.description(), actorId, changeReason));
        List<AcceptanceCriterion> written = insertCriteria(published, content.acceptanceCriteria(), usedKeys);
        requirement.setCurrentRevisionId(published.getId());
        return detail(requirement, published, written);
    }

    @Transactional
    public RequirementDetail changeStatus(long projectId, long actorId, long requirementId,
            RequirementStatus target) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Requirement requirement = require(projectId, requirementId);
        if (!ALLOWED_TARGETS.get(requirement.getStatus()).contains(target)) {
            throw ApiException.unprocessable("A " + requirement.getStatus()
                    + " requirement cannot move to " + target + ".");
        }
        requirement.setStatus(target);
        return detail(requirement, requirement.getCurrentRevision());
    }

    /**
     * 进入 IN_DEVELOPMENT 的唯一入口（api-contract 3）。指派被限制在「确有工作
     * 存在」的那两个状态里，因此 READY 的需求必定还没有被指派人，
     * 这里也就恰好是首次指派；此后的重新指派会看到 IN_DEVELOPMENT 并不动状态。
     * 「被指派人是本项目成员」由复合外键证明，而不是在这里检查。
     */
    @Transactional
    public RequirementDetail assign(long projectId, long actorId, long requirementId, long assigneeId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Requirement requirement = require(projectId, requirementId);
        if (requirement.getStatus() != RequirementStatus.READY
                && requirement.getStatus() != RequirementStatus.IN_DEVELOPMENT) {
            throw ApiException.conflict("Only a READY or IN_DEVELOPMENT requirement can be assigned.");
        }
        requirement.setAssigneeId(assigneeId);
        if (requirement.getStatus() == RequirementStatus.READY) {
            requirement.setStatus(RequirementStatus.IN_DEVELOPMENT);
        }
        return detail(requirement, requirement.getCurrentRevision());
    }

    private Requirement require(long projectId, long requirementId) {
        return requirements.findByProjectIdAndId(projectId, requirementId)
                .orElseThrow(ApiException::notFound);
    }

    /** DONE 与 CANCELED 是流转表没有给出任何出口的两个状态。 */
    private static boolean isTerminal(RequirementStatus status) {
        return ALLOWED_TARGETS.get(status).isEmpty();
    }

    /**
     * 把 {@code content} 的每一条验收条件都写成 {@code revision} 的新行：
     * 创建需求和发布新修订都用它，因为一次修订绝不与它的前驱共享行。
     * 带 key 的条目保留其 key，不带 key 的条目获得下一个从未用过的编号。
     */
    private List<AcceptanceCriterion> insertCriteria(RequirementRevision revision, List<CriterionInput> inputs,
            Set<String> usedKeys) {
        Set<String> available = new HashSet<>(usedKeys);
        int next = nextNumber(usedKeys);
        List<AcceptanceCriterion> rows = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            CriterionInput input = inputs.get(index);
            String key = input.acKey() == null ? AC_KEY_PREFIX + next++ : take(available, input.acKey());
            rows.add(new AcceptanceCriterion(revision.getProjectId(), revision.getId(), key,
                    index + 1, input.text()));
        }
        return criteria.saveAll(rows);
    }

    /**
     * 原地重写 DRAFT 修订的验收条件：已知 key 的条目编辑对应行，缺席的条目
     * 被删除，无 key 的条目则新增一行。DRAFT 需求恰好只有一次修订，
     * 因此那次修订的 key 集合就是它的全部 key 历史，下一个编号由此推出。
     */
    private List<AcceptanceCriterion> rewriteCriteria(RequirementRevision revision, List<CriterionInput> inputs) {
        Map<String, AcceptanceCriterion> current = criteria
                .findByProjectIdAndRequirementRevisionIdOrderBySortOrderAsc(revision.getProjectId(),
                        revision.getId())
                .stream().collect(Collectors.toMap(AcceptanceCriterion::getAcKey, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        int next = nextNumber(current.keySet());
        List<AcceptanceCriterion> rows = new ArrayList<>();
        List<AcceptanceCriterion> added = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            CriterionInput input = inputs.get(index);
            int sortOrder = index + 1;
            if (input.acKey() == null) {
                AcceptanceCriterion row = new AcceptanceCriterion(revision.getProjectId(), revision.getId(),
                        AC_KEY_PREFIX + next++, sortOrder, input.text());
                added.add(row);
                rows.add(row);
            } else {
                AcceptanceCriterion row = current.remove(input.acKey());
                if (row == null) {
                    throw ApiException.unprocessable(
                            "That acceptance criterion key does not belong to this requirement.");
                }
                row.edit(sortOrder, input.text());
                rows.add(row);
            }
        }
        criteria.deleteAll(current.values());
        criteria.saveAll(added);
        return rows;
    }

    /** 条目要保留的那个 key；若本需求从未用过它，或请求中重复出现，则拒绝。 */
    private static String take(Set<String> available, String requested) {
        if (!available.remove(requested)) {
            throw ApiException.unprocessable("That acceptance criterion key does not belong to this requirement.");
        }
        return requested;
    }

    /** 本需求的所有修订用过的最大编号加一。 */
    private static int nextNumber(Set<String> usedKeys) {
        return usedKeys.stream()
                .mapToInt(key -> Integer.parseInt(key.substring(AC_KEY_PREFIX.length())))
                .max().orElse(0) + 1;
    }

    private RequirementDetail detail(Requirement requirement, RequirementRevision revision) {
        return detail(requirement, revision, criteria
                .findByProjectIdAndRequirementRevisionIdOrderBySortOrderAsc(revision.getProjectId(),
                        revision.getId()));
    }

    private RequirementDetail detail(Requirement requirement, RequirementRevision revision,
            List<AcceptanceCriterion> acceptanceCriteria) {
        Map<Long, String> usernames =
                usernames(Stream.of(requirement.getAssigneeId(), revision.getCreatedBy()));
        return RequirementDetail.of(requirement, usernames.get(requirement.getAssigneeId()),
                RevisionView.of(revision, usernames.get(revision.getCreatedBy()), acceptanceCriteria));
    }

    /** 对响应中提到的所有账号做一次批量读取，因此没有任何路径会循环访问用户目录。 */
    private Map<Long, String> usernames(Stream<Long> userIds) {
        return users.byIds(userIds.filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AccountView::id, AccountView::username));
    }
}
