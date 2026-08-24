package com.forgepilot.scm;

import java.time.Instant;

record ScmIdentityResponse(long id, ScmProvider provider, String instanceIdentity,
        String externalUserId, String externalUsername, String label, ScmIdentityUsage usageType,
        ScmIdentity.VerificationStatus verificationStatus, Instant verifiedAt, Instant lastSyncedAt) {

    static ScmIdentityResponse of(ScmIdentity identity) {
        return new ScmIdentityResponse(identity.getId(), identity.getProvider(), identity.getInstanceIdentity(),
                identity.getExternalUserId(), identity.getExternalUsername(), identity.getLabel(),
                identity.getUsageType(), identity.getVerificationStatus(), identity.getVerifiedAt(),
                identity.getLastSyncedAt());
    }
}
