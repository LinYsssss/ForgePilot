package com.acme.modelio;

import java.util.List;

/**
 * Structured verdict summary returned by the review model for one diff slice.
 */
public record SummaryResponse(String verdict, List<String> highlights) {
}
