package com.example.codereview.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CryptoService {

    private static final String PREFIX = "v1";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;

    public CryptoService(@Value("${app.security.token-encrypt-key}") String key) {
        this.keySpec = new SecretKeySpec(sha256(key == null ? "" : key), "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX + ":" + base64(iv) + ":" + base64(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt sensitive value", ex);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (!cipherText.startsWith(PREFIX + ":")) {
            // 此前这里把非 v1 的值原样返回,等于"看起来解密成功了"。后果是:数据库里一旦
            // 出现明文凭据(历史数据、写入 bug、或被人直接改库),系统会照常拿去用,
            // 而没有任何信号表明这条记录根本没被加密过。改为失败关闭。
            throw new IllegalStateException("凭据不是 v1 加密格式，拒绝使用（可能是未加密的历史数据）");
        }
        try {
            String[] parts = cipherText.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid encrypted value format");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt sensitive value", ex);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive encryption key", ex);
        }
    }

    private String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
