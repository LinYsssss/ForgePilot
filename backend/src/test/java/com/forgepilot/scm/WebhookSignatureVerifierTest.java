package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signature verification needs no HTTP and no credential: it is a pure function of
 * the raw bytes, the secret and the header.
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(UTF_8);
    /** GitHub's own published example for {@code X-Hub-Signature-256}. */
    private static final String KNOWN_SIGNATURE =
            "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Pinned against the provider's documented vector rather than against this
     * implementation's own output, so "it agrees with itself" cannot pass for
     * correctness.
     */
    @Test
    void theProvidersOwnPublishedVectorVerifies() {
        assertThat(verifier.matches(BODY, SECRET, KNOWN_SIGNATURE)).isTrue();
        assertThat(verifier.matches(BODY, SECRET, KNOWN_SIGNATURE.toUpperCase(java.util.Locale.ROOT)
                .replace("SHA256=", "sha256="))).isTrue();
    }

    @Test
    void oneFlippedByteInTheBodyIsRejected() {
        assertThat(verifier.matches("Hello, World?".getBytes(UTF_8), SECRET, KNOWN_SIGNATURE)).isFalse();
        assertThat(verifier.matches("Hello, World! ".getBytes(UTF_8), SECRET, KNOWN_SIGNATURE)).isFalse();
        assertThat(verifier.matches(new byte[0], SECRET, KNOWN_SIGNATURE)).isFalse();
    }

    @Test
    void oneFlippedByteInTheSignatureOrTheSecretIsRejected() {
        assertThat(verifier.matches(BODY, SECRET, KNOWN_SIGNATURE.substring(0, KNOWN_SIGNATURE.length() - 1) + "8"))
                .isFalse();
        assertThat(verifier.matches(BODY, SECRET, KNOWN_SIGNATURE.substring(0, 40))).isFalse();
        assertThat(verifier.matches(BODY, "It's a Secret to Everybody!", KNOWN_SIGNATURE)).isFalse();
    }

    @Test
    void aSignatureThatIsMissingOrNotSha256IsRejected() {
        assertThat(verifier.matches(BODY, SECRET, null)).isFalse();
        assertThat(verifier.matches(BODY, SECRET, "")).isFalse();
        assertThat(verifier.matches(BODY, SECRET, KNOWN_SIGNATURE.substring("sha256=".length()))).isFalse();
        assertThat(verifier.matches(BODY, SECRET, "sha1=757107ea0eb2509fc211221cce984b8a37570b6d")).isFalse();
    }

    /**
     * The test this whole design exists for. Both bodies parse into the same JSON
     * document — the keys are reordered and whitespace added — but only the bytes
     * that were actually signed verify. An implementation that parsed the delivery
     * and re-serialized it before hashing would accept both, and would then be
     * authenticating something other than what it acts on.
     */
    @Test
    void aBodyThatParsesIdenticallyButHasDifferentBytesIsRejected() {
        byte[] signed = """
                {"action":"synchronize","number":7,"repository":{"id":123456}}"""
                .getBytes(UTF_8);
        byte[] reserialized = """
                { "number": 7, "repository": { "id": 123456 }, "action": "synchronize" }"""
                .getBytes(UTF_8);

        assertThat(json.readTree(reserialized))
                .as("the two bodies must be the same document, or this proves nothing")
                .isEqualTo(json.readTree(signed));
        assertThat(signed).isNotEqualTo(reserialized);

        String signature = sign(signed);
        assertThat(verifier.matches(signed, SECRET, signature)).isTrue();
        assertThat(verifier.matches(reserialized, SECRET, signature)).isFalse();
    }

    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
