package com.forgepilot.scm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the provider token and the webhook secret at rest (design.md 3.3).
 *
 * <p>AES-256-GCM under one symmetric key, taken from {@code forgepilot.scm.secret-key}
 * — which relaxed binding reads from the {@code FORGEPILOT_SCM_SECRET_KEY}
 * environment variable — and derived from it with SHA-256, so the deployment may
 * supply a secret of any length. <strong>There is no fallback and no default.</strong>
 * The value is read in this constructor, so a deployment without one fails at
 * startup rather than the first time someone connects a repository, exactly as
 * {@code FORGEPILOT_DB_PASSWORD} already behaves. The dangerous shape — quietly
 * encrypting under a weak built-in default — is therefore unreachable.
 *
 * <p>SHA-256 spreads the secret over the full key, it does not create entropy: a
 * deployment that supplies a guessable phrase has a guessable key. Compose and CI
 * carry deliberately fake local-only values for that reason.
 *
 * <p>Batch 2 does not rotate keys. Rotation needs a key version column and a
 * re-encryption pass, which is new structure; the gap is recorded rather than
 * papered over with a second key that is silently tried on failure.
 */
@Component
public class ScmSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    ScmSecretCipher(@Value("${forgepilot.scm.secret-key}") String secret) {
        if (secret.isBlank()) {
            throw new IllegalStateException(
                    "forgepilot.scm.secret-key (FORGEPILOT_SCM_SECRET_KEY) must not be empty.");
        }
        this.key = sha256(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] ciphertext = apply(Cipher.ENCRYPT_MODE, iv, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, envelope, 0, iv.length);
        System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(envelope);
    }

    public String decrypt(String stored) {
        byte[] envelope = Base64.getDecoder().decode(stored);
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(envelope, 0, iv, 0, IV_BYTES);
        byte[] ciphertext = new byte[envelope.length - IV_BYTES];
        System.arraycopy(envelope, IV_BYTES, ciphertext, 0, ciphertext.length);
        return new String(apply(Cipher.DECRYPT_MODE, iv, ciphertext), StandardCharsets.UTF_8);
    }

    private byte[] apply(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            // Never carries the key, the plaintext or the ciphertext.
            throw new IllegalStateException("An SCM credential could not be processed.");
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
