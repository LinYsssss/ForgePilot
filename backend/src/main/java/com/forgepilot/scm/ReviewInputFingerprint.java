package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The deterministic hash of a normalized review input (design.md 3.2, D003).
 *
 * <p>Inputs: the repository identity, base and head, and the changed-file manifest
 * with every patch. Explicitly <em>not</em> {@code source_revision} or
 * {@code source_updated_at} — those order events and must never mint an identity
 * of their own — and not the requirement association, which is a separate
 * component of Review identity.
 *
 * <p>Normalization: files in path byte order, paths case sensitive, patches as
 * UTF-8 bytes with line endings untouched (CRLF and LF are genuinely different
 * diffs, and folding them would collide two different inputs), and every field
 * framed by a NUL separator. NUL is the one byte that cannot occur in either a
 * path or a stored patch — PostgreSQL rejects it in text with 22021 — which makes
 * the encoding injective, so two different manifests cannot produce one digest.
 *
 * <p>This rule is frozen. Changing it invalidates every stored fingerprint and
 * therefore every Review identity that was ever derived from one, which is why
 * {@code ReviewInputFingerprintTest} pins an expected digest as a literal.
 */
final class ReviewInputFingerprint {

    private static final byte SEPARATOR = 0x00;
    private static final byte PATCH_PRESENT = '1';
    private static final byte PATCH_ABSENT = '0';

    private ReviewInputFingerprint() {
    }

    static String of(String provider, String instanceIdentity, String externalId,
            PullRequestSnapshot snapshot) {
        MessageDigest digest = sha256();
        field(digest, provider);
        field(digest, instanceIdentity);
        field(digest, externalId);
        field(digest, snapshot.baseSha());
        field(digest, snapshot.headSha());
        for (ChangedFile file : ChangedFile.canonicalOrder(snapshot.changedFiles())) {
            field(digest, file.path());
            field(digest, file.changeType());
            digest.update(file.patch() == null ? PATCH_ABSENT : PATCH_PRESENT);
            if (file.patch() != null) {
                digest.update(file.patch().getBytes(UTF_8));
            }
            digest.update(SEPARATOR);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void field(MessageDigest digest, String value) {
        digest.update(value.getBytes(UTF_8));
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
