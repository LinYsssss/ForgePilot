package com.forgepilot.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.forgepilot.review.ReviewOutput.FindingCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides where each finding of this round came from (ARCHITECTURE.md 3.6, D009).
 *
 * <p>Three rules, in this order, and the order is fixed at
 * {@code SUPPRESSED > PERSISTING > NEW}:
 *
 * <ol>
 * <li><strong>SUPPRESSED</strong> — the most recent human judgement on this
 * {@code finding_key} anywhere in this pull request was a rejection, and both
 * hashes are unchanged. Only then is a rejection inherited, so a suppression
 * cannot outlive the code or the requirement it was made about.</li>
 * <li><strong>PERSISTING</strong> — the immediately preceding COMPLETED Review of
 * this pull request reported the same key. It starts at {@code OPEN} again: the
 * problem persisting does not mean anyone decided about it.</li>
 * <li><strong>NEW</strong> — neither.</li>
 * </ol>
 *
 * <p>The opposite direction is deliberately not symmetric. A key the previous
 * round reported and this one did not is derived as
 * {@link #notReported(long, long, long) NOT_REPORTED} on the way out and never
 * stored: "the model did not mention it this time" is not evidence that anyone
 * fixed it, and a stored FIXED would be a machine closing a human's finding.
 */
@Service
public class FindingContinuityCalculator {

    private final FindingLineageRepository lineage;

    FindingContinuityCalculator(FindingLineageRepository lineage) {
        this.lineage = lineage;
    }

    /**
     * The lineage of every candidate of one Review, keyed by {@code finding_key}
     * (which is unique within a Review — {@code uq_finding_review_key}).
     *
     * <p>Called before the findings are written, because continuity and
     * {@code carried_from_finding_id} are columns of the rows being written.
     */
    @Transactional(readOnly = true)
    public Map<String, Lineage> lineageOf(long projectId, long pullRequestId, long reviewId,
            List<FindingCandidate> candidates) {
        Map<String, Finding> previousRound = previousRound(projectId, pullRequestId, reviewId).stream()
                .collect(Collectors.toMap(Finding::getFindingKey, finding -> finding));
        Map<String, Lineage> byKey = new LinkedHashMap<>();
        for (FindingCandidate candidate : candidates) {
            byKey.put(candidate.findingKey(), decide(projectId, pullRequestId, candidate,
                    previousRound.get(candidate.findingKey())));
        }
        return byKey;
    }

    /**
     * What the previous round reported and this one did not. Derived on read, never
     * stored: {@code ck_finding_status} has no {@code NOT_REPORTED} value, and 3.6.3
     * forbids turning this into an automatic fix.
     *
     * <p>Reads this Review's own findings from the database, so it answers about a
     * Review that has been written rather than one being computed.
     */
    @Transactional(readOnly = true)
    public List<Finding> notReported(long projectId, long pullRequestId, long reviewId) {
        Set<String> reportedNow = lineage.findFindingsOfReview(projectId, reviewId).stream()
                .map(Finding::getFindingKey)
                .collect(Collectors.toSet());
        return previousRound(projectId, pullRequestId, reviewId).stream()
                .filter(previous -> !reportedNow.contains(previous.getFindingKey()))
                .toList();
    }

    private Lineage decide(long projectId, long pullRequestId, FindingCandidate candidate, Finding matched) {
        Optional<Finding> rejected = lineage
                .findMostRecentlyRejectedFindingId(projectId, pullRequestId, candidate.findingKey())
                .flatMap(findingId -> lineage.findFinding(projectId, findingId));
        if (rejected.isPresent() && inheritable(rejected.get(), candidate)) {
            return new Lineage(FindingContinuity.SUPPRESSED, rejected.get().getId(), FindingStatus.REJECTED);
        }
        if (matched != null) {
            return new Lineage(FindingContinuity.PERSISTING, matched.getId(), FindingStatus.OPEN);
        }
        return new Lineage(FindingContinuity.NEW, null, FindingStatus.OPEN);
    }

    /**
     * Both hashes, not either. {@code evidence_hash} alone would keep suppressing a
     * finding after the acceptance criterion it was judged against was rewritten;
     * {@code basis_hash} alone would keep suppressing it after the code moved.
     */
    private static boolean inheritable(Finding rejected, FindingCandidate candidate) {
        return rejected.getEvidenceHash().equals(candidate.evidenceHash())
                && rejected.getBasisHash().equals(candidate.basisHash());
    }

    /** The previous COMPLETED round's findings, in row order, or nothing when this is the first round. */
    private List<Finding> previousRound(long projectId, long pullRequestId, long reviewId) {
        return lineage.findPreviousCompletedReviewId(projectId, pullRequestId, reviewId)
                .map(previousId -> lineage.findFindingsOfReview(projectId, previousId))
                .orElseGet(List::of);
    }

    /**
     * Where one finding came from and what status it starts in.
     *
     * <p>The constructor states the two invariants the database also states:
     * lineage exists exactly when something was inherited
     * ({@code ck_finding_carried_from_matches_continuity}), and only an inherited
     * suppression starts already rejected.
     */
    public record Lineage(FindingContinuity continuity, Long carriedFromFindingId, FindingStatus initialStatus) {

        public Lineage {
            if ((continuity == FindingContinuity.NEW) != (carriedFromFindingId == null)) {
                throw new IllegalArgumentException("Only an inherited finding carries a source finding.");
            }
            if ((continuity == FindingContinuity.SUPPRESSED) != (initialStatus == FindingStatus.REJECTED)) {
                throw new IllegalArgumentException("Only an inherited suppression starts rejected.");
            }
        }
    }
}
