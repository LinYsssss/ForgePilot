package com.acme.inventory;

import java.util.List;

/**
 * Outcome of a batch stock adjustment: how many items were applied and which item ids failed.
 */
public record BatchAdjustmentResult(int appliedCount, List<Long> failedItemIds) {
}
