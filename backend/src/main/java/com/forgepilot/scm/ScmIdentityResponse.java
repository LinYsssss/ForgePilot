package com.forgepilot.scm;

import java.time.Instant;

/** 身份的对外视图；没有 Token，也没有任何可用于重新认证的字段。 */
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
