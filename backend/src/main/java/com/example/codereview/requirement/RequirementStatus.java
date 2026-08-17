package com.example.codereview.requirement;

/**
 * 需求生命周期(父 design §3)。合法边集中在 {@link #canTransition};守卫(指派非空、
 * 编辑锁定)在 {@link RequirementService}——状态图只回答"这条边存在吗"。
 */
public enum RequirementStatus {
    DRAFT,
    NEEDS_IMPROVEMENT,
    READY,
    IN_DEVELOPMENT,
    IN_REVIEW,
    DONE,
    CANCELED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELED;
    }

    /** 进入开发后需求内容(标题/描述/AC)锁定,修改须先回退(R5)。 */
    public boolean locksContent() {
        return this == IN_DEVELOPMENT || this == IN_REVIEW || this == DONE;
    }

    public static boolean canTransition(RequirementStatus from, RequirementStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        if (to == CANCELED) {
            return !from.isTerminal();
        }
        return switch (from) {
            case DRAFT -> to == NEEDS_IMPROVEMENT || to == READY;
            case NEEDS_IMPROVEMENT -> to == READY;
            case READY -> to == NEEDS_IMPROVEMENT || to == IN_DEVELOPMENT;
            case IN_DEVELOPMENT -> to == IN_REVIEW || to == READY;
            case IN_REVIEW -> to == DONE || to == IN_DEVELOPMENT;
            case DONE, CANCELED -> false;
        };
    }

    public static RequirementStatus fromName(String name) {
        for (RequirementStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return null;
    }
}
