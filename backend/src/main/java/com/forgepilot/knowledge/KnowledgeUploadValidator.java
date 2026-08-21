package com.forgepilot.knowledge;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Component;

/**
 * Rejects text that must never reach the database, before any of it is chunked
 * or embedded.
 *
 * <p>This is not defensive duplication of a constraint. Two of these four checks
 * the database cannot make at all, and running the other two early avoids paying
 * an embedding provider for input that provably cannot land.
 */
@Component
public class KnowledgeUploadValidator {

    /** ARCHITECTURE.md 7.2. The varlena limit is no defence: a 600 MB text was measured landing fine. */
    static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    static final int MAX_TITLE_LENGTH = 255;

    public void validate(String title, String text) {
        if (title == null || title.isBlank()) {
            throw ApiException.unprocessable("A document needs a title.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.unprocessable("The title is longer than " + MAX_TITLE_LENGTH + " characters.");
        }
        if (text == null || text.isBlank()) {
            throw ApiException.unprocessable("The document has no readable text.");
        }
        rejectNulByte(text);
        rejectUnencodableText(text);
        rejectOversizedText(text);
    }

    /**
     * PostgreSQL rejects this with 22021, but only after chunking and embedding
     * have already been paid for.
     */
    private void rejectNulByte(String text) {
        if (text.indexOf('\0') >= 0) {
            throw ApiException.unprocessable("The document text contains a NUL byte.");
        }
    }

    /**
     * The one case the database cannot catch. Measured: a lone UTF-16 surrogate is
     * silently replaced with '?' by the JDBC driver, so PostgreSQL receives valid
     * UTF-8 and never raises 22021 — the text is corrupted with no error anywhere
     * (D015.5). {@link CharsetEncoder#canEncode} is where that becomes visible.
     */
    private void rejectUnencodableText(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        if (!encoder.canEncode(text)) {
            throw ApiException.unprocessable(
                    "The document text is not valid Unicode; it contains an unpaired surrogate.");
        }
    }

    /** Counted in UTF-8 bytes, because that is what the column stores. */
    private void rejectOversizedText(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES) {
            throw ApiException.unprocessable(
                    "The document is " + bytes + " bytes, over the " + MAX_TEXT_BYTES + " byte limit.");
        }
    }
}
