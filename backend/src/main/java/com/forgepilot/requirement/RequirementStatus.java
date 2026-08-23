package com.forgepilot.requirement;

/** 需求生命周期。状态流转定义在 design.md 6.4（D013.4）。 */
public enum RequirementStatus {
    DRAFT,
    READY,
    IN_DEVELOPMENT,
    DONE,
    CANCELED
}
