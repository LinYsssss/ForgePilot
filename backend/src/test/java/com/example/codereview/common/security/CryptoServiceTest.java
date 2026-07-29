package com.example.codereview.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private final CryptoService cryptoService = new CryptoService("unit-test-encryption-key");

    @Test
    void encryptAndDecryptSensitiveValue() {
        String encrypted = cryptoService.encrypt("secret-token");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("secret-token");
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo("secret-token");
    }

    @Test
    void blankValueReturnsNull() {
        assertThat(cryptoService.encrypt("")).isNull();
        assertThat(cryptoService.decrypt("")).isNull();
        assertThat(cryptoService.encrypt(null)).isNull();
        assertThat(cryptoService.decrypt(null)).isNull();
    }

    @Test
    void nonV1ValueIsRejectedRatherThanPassedThrough() {
        // 旧行为是把非 v1 的值原样返回,于是库里的明文会被当成"解密成功"的结果照常使用,
        // 且没有任何信号表明这条记录从未被加密。现在必须失败关闭。
        assertThatThrownBy(() -> cryptoService.decrypt("legacy-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v1");

        assertThatThrownBy(() -> cryptoService.decrypt("v2:iv:payload"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameInputEncryptsDifferentlyEachTime() {
        // 随机 IV:密文相等就能推断凭据相同,所以不允许确定性输出
        assertThat(cryptoService.encrypt("same")).isNotEqualTo(cryptoService.encrypt("same"));
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        String encrypted = cryptoService.encrypt("payload");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        // GCM 带认证标签,改动密文必须被识破,而不是解出垃圾数据
        assertThatThrownBy(() -> cryptoService.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String encrypted = cryptoService.encrypt("payload");
        CryptoService other = new CryptoService("a-completely-different-key");

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
