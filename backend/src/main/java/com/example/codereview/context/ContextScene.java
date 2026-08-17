package com.example.codereview.context;

/** 上下文场景(父 design §5)。P2 落地 REQUIREMENT_CHECK;其余场景随对应 Phase 接入。 */
public enum ContextScene {
    REQUIREMENT_CHECK,
    PR_REVIEW,
    ASSISTANT
}
