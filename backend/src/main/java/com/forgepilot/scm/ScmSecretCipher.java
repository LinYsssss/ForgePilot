package com.forgepilot.scm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
 * <p>AES-256-GCM under one symmetric key, supplied as base64 of 32 bytes through
 * {@code forgepilot.scm.secret-key}, which relaxed binding reads from the
 * {@code FORGEPILOT_SCM_SECRET_KEY} environment variable. <strong>There is no
 * fallback key.</strong> Without one this bean refuses to encrypt or decrypt
 * anything, so no repository can be registered and no delivery can be verified —
 * the dangerous shape, quietly encrypting under a weak built-in default, cannot
 * occur.
 *
 * <p>Deviation from design.md 3.3, recorded rather than hidden: that ruling asks
 * for the application to <em>fail at startup</em> when the key is missing, matching
 * {@code FORGEPILOT_DB_PASSWORD}. Doing that needs the property declared in
 * {@code application.yml}, and a property with no default would break the startup
 * of every other Spring context in this repository. This slice may not edit that
 * file, so the refusal happens at first use instead of at startup.
 *
 * <p>Batch 2 does not rotate keys: a rotation needs a key version column and a
 * re-encryption pass, which is new structure.
 */
@Component
public class ScmSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    ScmSecretCipher(@Value("${forgepilot.scm.secret-key:}") String configuredKey) {
        this.key = configuredKey.isBlank() ? null : Base64.getDecoder().decode(configuredKey);
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
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "forgepilot.scm.secret-key must hold base64 of 32 bytes; SCM credentials are unusable without it.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            // The message never carries the key, the plaintext or the ciphertext.
            throw new IllegalStateException("An SCM credential could not be processed.");
        }
    }
}
