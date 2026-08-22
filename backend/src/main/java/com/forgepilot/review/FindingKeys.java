package com.forgepilot.review;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The three deterministic keys a Finding carries across rounds (D009,
 * ARCHITECTURE.md 3.6.1 and 3.6.2), computed the same way
 * {@code ReviewInputFingerprint} computes its digest: every field is framed by a
 * NUL byte, which cannot occur in a path, an excerpt or a stored patch, so the
 * encoding is injective and two different inputs cannot collide into one digest.
 *
 * <p><strong>Neither hash covers a single character the model wrote as prose.</strong>
 * That is the whole point of the mechanism. A rejection is inherited only when
 * both hashes are unchanged, so if a hash covered the model's description, the
 * next round's rewording would either lose a valid suppression or — worse —
 * suppress a finding whose code or requirement had actually moved underneath it.
 *
 * <p>All three are frozen rules. {@link #RULE_VERSION} exists so that changing
 * them is a deliberate act with a visible consequence rather than a silent one.
 */
final class FindingKeys {

    /**
     * Part of every {@code basis_hash}. Bumping it changes every basis hash and
     * therefore drops every inherited suppression, which is the intended effect:
     * if the deterministic rules change, a human's earlier rejection was made
     * against a different basis and must not carry over unexamined.
     */
    static final String RULE_VERSION = "1";

    /**
     * The one volatile-line-number form this pipeline actually handles. Patches
     * arrive from the provider as unified diffs, and an unrelated edit above a
     * hunk shifts these numbers without changing a byte of the evidence.
     *
     * <p>The trailing context after the closing {@code @@} is deliberately kept —
     * it is source. And no other "line number" shape is stripped: a rule that
     * removed a leading {@code 42: } gutter would also mutilate any real source
     * line beginning with a number, and this codebase never produces that shape.
     */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("(?m)^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@");
    private static final String HUNK_HEADER_PLACEHOLDER = "@@ @@";

    private static final byte SEPARATOR = 0x00;
    private static final byte PRESENT = '1';
    private static final byte ABSENT = '0';

    private FindingKeys() {
    }

    /**
     * Does two jobs at once (3.6.1): dedup inside one Review and matching across
     * Reviews of the same pull request.
     *
     * <p>{@code path} is case sensitive and never lower-cased — {@code Api.java}
     * and {@code api.java} are two files on a case sensitive checkout, and folding
     * them would let a rejection on one suppress a finding in the other.
     * A {@code REQUIREMENT} finding additionally carries {@code requirementId} and
     * {@code acKey}; {@code acKey} rather than the acceptance criterion's row id,
     * because the row id changes with every published revision while the business
     * identity it names does not (D011).
     *
     * <p>The result is a digest rather than a readable concatenation because the
     * column is {@code VARCHAR(255)}: a legitimately deep path plus a category
     * would exceed it, and PostgreSQL would reject the insert (22001) — while
     * truncating to fit would silently merge two different findings into one key.
     */
    static String findingKey(FindingType findingType, String path, Integer line, String category,
            Long requirementId, String acKey) {
        MessageDigest digest = sha256();
        field(digest, findingType.name());
        field(digest, path);
        // The normalized position: the line if the patch could verify one, absent
        // otherwise (3.5 forbids emitting a line that could not be verified).
        field(digest, line == null ? null : line.toString());
        field(digest, normalizeCategory(category));
        field(digest, requirementId == null ? null : requirementId.toString());
        field(digest, acKey);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Covers deterministic source evidence only: line endings unified, the diff
     * hunk header's volatile numbers replaced, and nothing else touched.
     *
     * <p>Whitespace is <strong>not</strong> collapsed. Python and YAML mean
     * different things at different indents, so a general fold would make two
     * genuinely different pieces of source hash alike — and a suppression that
     * ignores indentation is a suppression that survives a real change.
     */
    static String evidenceHash(String excerpt) {
        MessageDigest digest = sha256();
        field(digest, normalizeEvidence(excerpt));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Covers what the finding was judged <em>against</em>: the cited requirement
     * and acceptance criterion as they read in this Review's revision, the hashes
     * of the knowledge excerpts that were recalled, and the rule version.
     *
     * <p>Excerpt hashes are sorted and de-duplicated so that the provider's recall
     * order cannot change the result — the set of cited sources is the fact, their
     * order is not. The excerpts enter as their own hashes rather than their text:
     * ARCHITECTURE.md 3.5 stores an immutable excerpt plus hash exactly so that a
     * later edit to the knowledge document cannot rewrite what a past Review meant.
     */
    static String basisHash(String requirementText, String acKey, String acText,
            Collection<String> knowledgeExcerptHashes) {
        MessageDigest digest = sha256();
        field(digest, RULE_VERSION);
        field(digest, requirementText);
        field(digest, acKey);
        field(digest, acText);
        knowledgeExcerptHashes.stream().distinct().sorted().forEach(hash -> field(digest, hash));
        return HexFormat.of().formatHex(digest.digest());
    }

    static String normalizeEvidence(String excerpt) {
        String unifiedNewlines = excerpt.replace("\r\n", "\n").replace('\r', '\n');
        return HUNK_HEADER.matcher(unifiedNewlines).replaceAll(HUNK_HEADER_PLACEHOLDER);
    }

    /**
     * The category is a label from the answer schema, not prose, and it takes part
     * in the key rather than in a hash. Case and surrounding space are folded here
     * — and only here — because {@code Null deref} and {@code null-deref} name one
     * category, whereas two paths differing in case name two files.
     */
    static String normalizeCategory(String category) {
        return category == null ? "" : category.strip().toLowerCase(Locale.ROOT);
    }

    /** Absent and empty must not hash alike, so presence is its own byte. */
    private static void field(MessageDigest digest, String value) {
        digest.update(value == null ? ABSENT : PRESENT);
        if (value != null) {
            digest.update(value.getBytes(UTF_8));
        }
        digest.update(SEPARATOR);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
