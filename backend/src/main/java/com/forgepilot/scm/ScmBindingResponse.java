package com.forgepilot.scm;

import java.time.Instant;

record ScmBindingResponse(long id, long userId, long identityId, String label,
        ScmIdentityUsage usageType, ScmProvider provider, String instanceIdentity,
        String externalUserId, String externalUsername, ProjectMemberScmBinding.Status status,
        ProjectMemberScmBinding.AccessLevel accessLevel, Instant accessCheckedAt,
        Long approvedBy, Instant requestedAt, Instant decidedAt, Instant activatedAt, Instant endedAt) {

    static ScmBindingResponse of(ProjectMemberScmBinding binding, ScmIdentity identity) {
        return new ScmBindingResponse(binding.getId(), binding.getUserId(), identity.getId(),
                identity.getLabel(), identity.getUsageType(), identity.getProvider(),
                identity.getInstanceIdentity(), identity.getExternalUserId(), identity.getExternalUsername(),
                binding.getStatus(), binding.getAccessLevel(), binding.getAccessCheckedAt(),
                binding.getApprovedBy(), binding.getRequestedAt(), binding.getDecidedAt(),
                binding.getActivatedAt(), binding.getEndedAt());
    }
}
