package com.example.codereview.finding;

/**
 * Finding 生命周期(P5,父 design §8)。与 pipeline 校验态({@code Finding.status})正交。
 * 边:OPEN→CONFIRMED→IN_PROGRESS→FIXED→VERIFIED→CLOSED;OPEN/CONFIRMED→REJECTED;
 * FIXED→IN_PROGRESS(验证打回)。终态 REJECTED/CLOSED。
 */
public enum FindingLifecycle {
    OPEN,
    CONFIRMED,
    IN_PROGRESS,
    FIXED,
    VERIFIED,
    CLOSED,
    REJECTED;

    public boolean isTerminal() {
        return this == CLOSED || this == REJECTED;
    }

    public static boolean canTransition(FindingLifecycle from, FindingLifecycle to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return switch (from) {
            case OPEN -> to == CONFIRMED || to == REJECTED;
            case CONFIRMED -> to == IN_PROGRESS || to == REJECTED;
            case IN_PROGRESS -> to == FIXED;
            case FIXED -> to == VERIFIED || to == IN_PROGRESS;
            case VERIFIED -> to == CLOSED;
            case CLOSED, REJECTED -> false;
        };
    }

    public static FindingLifecycle fromName(String name) {
        for (FindingLifecycle value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
