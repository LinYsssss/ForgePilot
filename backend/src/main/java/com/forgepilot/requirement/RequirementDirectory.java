package com.forgepilot.requirement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way another feature may ask about a requirement (D015.6). It is a
 * query facade, not a repository, so {@code scm} stays free of
 * {@code RequirementRepository} and of everything about how requirements work.
 *
 * <p>{@code scm} needs this because {@code REQ-<n>} has to be resolved
 * <em>before</em> the pull request row is written: the composite foreign key can
 * only fail the whole insert, and catching that violation to carry on is
 * forbidden (D013.11), while D007 requires a bad reference not to block
 * ingestion at all.
 */
@Service
@Transactional(readOnly = true)
public class RequirementDirectory {

    private final RequirementRepository requirements;

    RequirementDirectory(RequirementRepository requirements) {
        this.requirements = requirements;
    }

    /**
     * Whether this requirement exists <em>in this project</em>. An id belonging to
     * another project answers false, which is what turns a cross-project
     * {@code REQ-<n>} into "no linked requirement" rather than an error (D013.2).
     */
    public boolean existsInProject(long projectId, long requirementId) {
        return requirements.findByProjectIdAndId(projectId, requirementId).isPresent();
    }
}
