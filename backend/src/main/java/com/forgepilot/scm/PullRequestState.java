package com.forgepilot.scm;

/** Provider 已核验的远端状态；历史未同步用 null，不推断为 OPEN。 */
public enum PullRequestState {
    OPEN, CLOSED, MERGED
}
