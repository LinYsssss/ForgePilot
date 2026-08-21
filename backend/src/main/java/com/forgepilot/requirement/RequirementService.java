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
 * Requirements, their immutable revisions and the state machine that governs
 * both (design.md 6.4). Authorization goes through {@link ProjectAccessService}
 * and usernames through {@link UserDirectory}: this feature injects neither
 * {@code ProjectMemberRepository} nor {@code UserAccountRepository} (D013.6).
 *
 * <p>Constraint conflicts — a cross-project parent, an assignee who is not a
 * member, a duplicate revision seq — are left to the database and roll their
 * transaction back (D013.11); none of them is caught and worked around here.
 */
@Service
public class RequirementService {

    private static final String AC_KEY_PREFIX = "AC-";

    /**
     * The transition table of api-contract 3, as data. {@code IN_DEVELOPMENT} is
     * deliberately absent from every value: its only entry is the first
     * assignment, so {@link #changeStatus} can never reach it.
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
     * Three steps in one transaction (D013.10): insert the requirement with a null
     * pointer, insert revision 1 and its criteria, then backfill the pointer. The
     * composite foreign key is MATCH SIMPLE, so it is skipped while the pointer is
     * null and checked in full by the backfill.
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
     * In-place edit of revision 1, legal only while the requirement is still DRAFT.
     * The quality result described the old prose, so it is cleared in the same
     * transaction (design.md 6.4).
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
     * Once the requirement has left DRAFT its revision is frozen, so a change
     * publishes {@code seq + 1} with a mandatory reason and backfills the pointer
     * again. The current revision is always the newest one, which is why its seq
     * is the one incremented; a concurrent second publish loses on the unique key
     * {@code (requirement_id, seq)}.
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
     * The only entry into IN_DEVELOPMENT (api-contract 3). Assignment is confined
     * to the two states where work exists, so a READY requirement still has no
     * assignee and this is exactly the first assignment; a later reassignment
     * finds IN_DEVELOPMENT and leaves the status alone. That the assignee is a
     * member of this project is proven by the composite foreign key, not here.
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

    /** DONE and CANCELED are the states the table gives no way out of. */
    private static boolean isTerminal(RequirementStatus status) {
        return ALLOWED_TARGETS.get(status).isEmpty();
    }

    /**
     * Writes every criterion of {@code content} as a new row of {@code revision}:
     * used both when the requirement is created and when a revision is published,
     * because a revision never shares rows with its predecessor. Entries that
     * carry a key keep it, entries without one get the next never-used number.
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
     * Rewrites the criteria of a DRAFT revision in place: a known key edits its
     * row, a missing entry drops it and a keyless entry adds one. A DRAFT
     * requirement has exactly one revision, so that revision's keys are its whole
     * key history and the next number is derived from them.
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

    /** The key an entry keeps, refusing one that this requirement never used or that the request repeats. */
    private static String take(Set<String> available, String requested) {
        if (!available.remove(requested)) {
            throw ApiException.unprocessable("That acceptance criterion key does not belong to this requirement.");
        }
        return requested;
    }

    /** One more than the highest number any revision of this requirement has used. */
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

    /** One batch read for every account a response mentions, so no path loops over the directory. */
    private Map<Long, String> usernames(Stream<Long> userIds) {
        return users.byIds(userIds.filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AccountView::id, AccountView::username));
    }
}
