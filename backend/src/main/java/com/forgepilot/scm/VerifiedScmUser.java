package com.forgepilot.scm;

/** Provider「当前用户」接口的归一化结果：身份三元组加当前用户名。 */
record VerifiedScmUser(ScmProvider provider, String instanceIdentity,
        String externalUserId, String externalUsername) {
}
