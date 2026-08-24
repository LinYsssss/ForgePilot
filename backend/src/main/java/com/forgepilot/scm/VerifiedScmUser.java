package com.forgepilot.scm;

record VerifiedScmUser(ScmProvider provider, String instanceIdentity,
        String externalUserId, String externalUsername) {
}
