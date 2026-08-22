package com.forgepilot.scm;

import java.time.Instant;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.RequirementReferenceParser.RequirementReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns an authenticated delivery plus an authoritative provider snapshot into the
 * pull request row.
 */
@Service
public class PullRequestSyncService {

    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final PullRequestRequirementEventRepository events;
    private final RequirementReferenceParser references;
    private final WebhookSignatureVerifier verifier;
    private final ScmSecretCipher cipher;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json;

    PullRequestSyncService(ScmRepositoryRepository repositories, PullRequestRepository pullRequests,
            PullRequestRequirementEventRepository events, RequirementReferenceParser references,
            WebhookSignatureVerifier verifier, ScmSecretCipher cipher,
            ApplicationEventPublisher publisher, ObjectMapper json) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.events = events;
        this.references = references;
        this.verifier = verifier;
        this.cipher = cipher;
        this.publisher = publisher;
        this.json = json;
    }

    /**
     * Routes the delivery to its repository and verifies the signature over the raw
     * bytes, writing nothing and calling nothing outbound on the way.
     *
     * <p>The lookup has to precede verification, because the secret to verify with
     * is a column of the row being looked up. Both failures answer 401 with one
     * body: telling "no such repository" apart from "bad signature" would let
     * anyone enumerate which repositories this deployment has connected — the same
     * rule as batch 1's "missing and forbidden are indistinguishable".
     */
    @Transactional(readOnly = true)
    public ScmRepository authenticate(ScmProvider provider, String instanceIdentity, String externalId,
            byte[] body, String signatureHeader) {
        ScmRepository repository = repositories
                .findByProviderAndInstanceIdentityAndExternalId(provider, instanceIdentity, externalId)
                .orElseThrow(PullRequestSyncService::unauthenticated);
        if (!verifier.matches(body, cipher.decrypt(repository.getEncryptedSecret()), signatureHeader)) {
            throw unauthenticated();
        }
        return repository;
    }

    @Transactional
    public void apply(ScmRepository detached, PullRequestSnapshot snapshot) {
        // Re-read inside this transaction rather than trusting the row that
        // authenticate() read and committed. The provider fetch happens between the
        // two, and an identity update can commit in that window: the triple freeze
        // only refuses a change while pull requests already exist, so an update that
        // ran before this insert is legal, and this delivery would then attach a
        // pull request to a repository whose identity had just moved -- storing a
        // fingerprint computed from the old triple, which is exactly the
        // irreproducibility design.md 3.7 exists to prevent.
        //
        // Locked, not merely re-read. An unlocked read narrows that window without
        // closing it: measured, an identity update can still commit between this
        // read and the insert below, because the insert only takes FOR KEY SHARE on
        // this row and the updater's freeze check runs while no pull request exists
        // yet. The result is a stored fingerprint that can never be recomputed from
        // the row it belongs to. project_id is taken from the row authenticate()
        // already resolved: a repository never moves between projects.
        ScmRepository repository = repositories
                .findWithLockByProjectIdAndId(detached.getProjectId(), detached.getId())
                .orElseThrow(PullRequestSyncService::unauthenticated);
        long projectId = repository.getProjectId();
        String fingerprint = ReviewInputFingerprint.of(repository.getProvider().name(),
                repository.getInstanceIdentity(), repository.getExternalId(), snapshot);
        String manifest = manifest(snapshot.changedFiles());

        PullRequest existing = pullRequests.findWithLockByProjectIdAndRepositoryIdAndExternalNumber(
                projectId, repository.getId(), snapshot.externalNumber()).orElse(null);
        if (existing != null) {
            // "Not older", per ARCHITECTURE.md 3.1: an equal timestamp still writes,
            // because the provider's resolution is one second and two pushes can
            // share it, and the values come from a fresh read anyway.
            if (isOlderThan(snapshot.sourceUpdatedAt(), existing.getSourceUpdatedAt())) {
                return;
            }
            existing.applySnapshot(snapshot.baseSha(), snapshot.headSha(), snapshot.title(), fingerprint,
                    manifest, snapshot.sourceRevision(), snapshot.sourceUpdatedAt());
            pullRequests.flush();
            publish(existing);
            return;
        }

        PullRequest created = new PullRequest(projectId, repository.getId(), snapshot.externalNumber(),
                snapshot.title(), snapshot.authorExternalUserId(), snapshot.authorUsername());
        created.applySnapshot(snapshot.baseSha(), snapshot.headSha(), snapshot.title(), fingerprint,
                manifest, snapshot.sourceRevision(), snapshot.sourceUpdatedAt());
        // Parsed once, when the row is created. A later delivery must not re-parse
        // over a human correction, and D007 gives the page the last word on the
        // association; an unresolvable reference simply leaves it null and never
        // blocks ingestion.
        RequirementReference reference = references
                .resolve(projectId, snapshot.headRef(), snapshot.title()).orElse(null);
        created.linkRequirement(reference == null ? null : reference.requirementId());
        pullRequests.saveAndFlush(created);
        if (reference != null) {
            events.save(PullRequestRequirementEvent.systemLink(projectId, created.getId(),
                    reference.requirementId(),
                    "Linked from REQ-" + reference.requirementId() + " in the pull request " + reference.source()));
        }
        publish(created);
    }

    /**
     * Refuses rather than guesses when the delivery carries no ordering value.
     * Returning false there would make the guard vanish silently and let an
     * out-of-order delivery roll head, base and the patches backwards -- the one
     * thing R4 forbids. GitHub always sends updated_at, so this is latent today and
     * becomes live the moment a provider that omits it is added.
     */
    private static boolean isOlderThan(Instant incoming, Instant current) {
        if (current == null) {
            return false;
        }
        if (incoming == null) {
            throw ApiException.unprocessable(
                    "The delivery carries no source timestamp, so it cannot be ordered "
                            + "against what is already stored.");
        }
        return incoming.isBefore(current);
    }

    /**
     * The row is flushed before the event, so a listener joining this transaction
     * can actually read the row it is being told about.
     */
    private void publish(PullRequest pullRequest) {
        publisher.publishEvent(new PullRequestChanged(pullRequest.getId(), pullRequest.getHeadSha(),
                pullRequest.getReviewInputFingerprint()));
    }

    /**
     * The manifest and every patch, as one JSONB value (D015.7). Over the limit the
     * ingestion fails explicitly rather than storing a silently shortened diff that
     * a Review would then be told was complete. There is no column on
     * {@code pull_request} to mark such a delivery, so nothing is written at all.
     */
    private String manifest(List<ChangedFile> changedFiles) {
        String manifest = json.writeValueAsString(ChangedFile.canonicalOrder(changedFiles));
        if (manifest.length() > ChangedFile.MAX_TOTAL_CHARS) {
            throw ApiException.unprocessable("This pull request's diff is larger than this deployment stores.");
        }
        return manifest;
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "The delivery could not be verified.");
    }
}
