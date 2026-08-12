package com.acme.profile;

/**
 * Reputation-based badge tiers rendered next to a profile display name.
 */
public enum BadgeTier {
    GOLD(1000, "gold"),
    SILVER(100, "silver"),
    BRONZE(0, "bronze");

    private final int minReputation;
    private final String label;

    BadgeTier(int minReputation, String label) {
        this.minReputation = minReputation;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static BadgeTier forReputation(int reputation) {
        for (BadgeTier tier : values()) {
            if (reputation >= tier.minReputation) {
                return tier;
            }
        }
        return BRONZE;
    }
}
