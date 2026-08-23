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
 * 对 provider token 与 webhook 密钥做静态加密（design.md 3.3）。
 *
 * <p>使用单个对称密钥下的 AES-256-GCM，密钥取自
 * {@code forgepilot.scm.secret-key}——宽松绑定会从 {@code FORGEPILOT_SCM_SECRET_KEY}
 * 环境变量读取——并用 SHA-256 派生，因此部署方可以提供任意长度的秘密值。
 * <strong>没有任何兜底，也没有任何默认值。</strong>该值在本构造器中读取，
 * 因此没有配置它的部署会在**启动时**失败，而不是等到有人第一次接入仓库时才失败，
 * 这与 {@code FORGEPILOT_DB_PASSWORD} 的既有行为完全一致。于是最危险的那种形态
 * ——用一个内置弱默认值悄悄加密——根本不可达。
 *
 * <p>SHA-256 只是把秘密值摊开到整个密钥空间，它并不创造熵：提供一个容易猜到的
 * 短语，就会得到一个容易猜到的密钥。Compose 与 CI 里带的都是刻意伪造的、
 * 仅限本地使用的值，原因正在于此。
 *
 * <p>批次 2 不做密钥轮换。轮换需要一个密钥版本列加一次重加密扫描，那是新的结构；
 * 这个缺口被如实记录下来，而不是用「失败时再静默试第二把密钥」来遮掩过去。
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
            // 绝不携带密钥、明文或密文。
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
