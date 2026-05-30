package com.example.codereview.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    @Test
    void encryptAndDecryptSensitiveValue() {
        CryptoService cryptoService = new CryptoService("unit-test-encryption-key");

        String encrypted = cryptoService.encrypt("secret-token");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("secret-token");
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo("secret-token");
    }

    @Test
    void blankValueReturnsNull() {
        CryptoService cryptoService = new CryptoService("unit-test-encryption-key");

        assertThat(cryptoService.encrypt("")).isNull();
        assertThat(cryptoService.decrypt("")).isNull();
    }

    @Test
    void legacyPlainTextCanStillBeRead() {
        CryptoService cryptoService = new CryptoService("unit-test-encryption-key");

        assertThat(cryptoService.decrypt("legacy-token")).isEqualTo("legacy-token");
    }
}
