package com.forgepilot.scm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.forgepilot.requirement.RequirementDirectory;
import org.springframework.stereotype.Component;

/**
 * Resolves the first {@code REQ-<n>} in a pull request's branch name or title,
 * where {@code <n>} is the global {@code requirement.id} (D013.2).
 *
 * <p>Resolution is filtered by the pull request's own project, so an id that
 * belongs to another project resolves to "no linked requirement" exactly as if
 * there had been no token at all — not an error, and never a block on ingestion
 * (D007). The composite foreign key cannot do that filtering: it would fail the
 * whole insert, and catching that to carry on is forbidden (D013.11). So the
 * question is asked before the row is written, through {@code requirement}'s
 * read-only facade (D015.6); this class must never see a repository.
 */
@Component
class RequirementReferenceParser {

    /**
     * Case sensitive and anchored on a non-alphanumeric boundary. Every document
     * writes the token as {@code REQ-}, so a lenient match would be an invention;
     * the boundary keeps {@code PREQ-7} from being read as a reference.
     */
    private static final Pattern REFERENCE = Pattern.compile("(?<![A-Za-z0-9])REQ-(\\d{1,18})");

    private final RequirementDirectory requirements;

    RequirementReferenceParser(RequirementDirectory requirements) {
        this.requirements = requirements;
    }

    /** The branch wins over the title: it is the one a developer has to type correctly to push. */
    Optional<RequirementReference> resolve(long projectId, String branch, String title) {
        return firstReference(branch, "branch")
                .or(() -> firstReference(title, "title"))
                .filter(reference -> requirements.existsInProject(projectId, reference.requirementId()));
    }

    static Optional<RequirementReference> firstReference(String text, String source) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new RequirementReference(Long.parseLong(matcher.group(1)), source));
    }

    record RequirementReference(long requirementId, String source) {
    }
}
