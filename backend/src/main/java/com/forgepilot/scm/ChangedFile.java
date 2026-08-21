package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One entry of a pull request's changed-file manifest.
 *
 * <p>{@code patch} is null when the provider supplied none — a binary file, or a
 * file past the provider's own diff limit. Absent is not the same as empty and the
 * two must not hash alike, so {@link ReviewInputFingerprint} encodes the
 * difference explicitly. {@code changeType} is stored exactly as the provider
 * reported it; normalizing it here would change what the fingerprint covers.
 */
public record ChangedFile(String path, String changeType, String patch) {

    /**
     * The manifest is stored as one JSONB value on the pull request row (D015.7),
     * so it has to stay a row. Over this many characters the ingestion fails
     * explicitly instead of silently truncating what a Review would later be told
     * it had seen (D002).
     */
    public static final int MAX_TOTAL_CHARS = 4_000_000;

    /**
     * Sorted by the path's UTF-8 bytes, unsigned. The provider's page order is
     * conventional rather than contractual — a 300 file pull request spans several
     * pages — so the fingerprint would otherwise depend on pagination. Byte order,
     * not locale order, and paths stay case sensitive.
     */
    public static List<ChangedFile> canonicalOrder(List<ChangedFile> files) {
        return files.stream()
                .sorted(Comparator.comparing(file -> file.path().getBytes(UTF_8), Arrays::compareUnsigned))
                .toList();
    }
}
