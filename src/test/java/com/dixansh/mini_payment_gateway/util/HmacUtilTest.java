package com.dixansh.mini_payment_gateway.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the webhook HMAC signer. The headline assertion pins the output to a
 * <b>known-answer test vector</b> (RFC 4231, Test Case 2) rather than re-deriving the expected value
 * with the same code under test - so the test would catch an algorithm/encoding regression, not just
 * agree with itself.
 */
class HmacUtilTest {

    // RFC 4231 §4.3, Test Case 2: HMAC-SHA-256(key="Jefe", data="what do ya want for nothing?").
    private static final String RFC_KEY = "Jefe";
    private static final String RFC_DATA = "what do ya want for nothing?";
    private static final String RFC_EXPECTED_HEX =
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

    @Test
    void sign_matchesRfc4231KnownVector() {
        assertThat(HmacUtil.sign(RFC_KEY, RFC_DATA)).isEqualTo("sha256=" + RFC_EXPECTED_HEX);
    }

    @Test
    void sign_isLowercaseHexWithPrefix() {
        String sig = HmacUtil.sign("secret", "{\"event\":\"payment.completed\"}");
        assertThat(sig).startsWith("sha256=");
        // 7-char prefix + 64 hex chars (SHA-256 = 32 bytes).
        assertThat(sig.substring("sha256=".length())).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void sign_isDeterministic() {
        assertThat(HmacUtil.sign("k", "body")).isEqualTo(HmacUtil.sign("k", "body"));
    }

    @Test
    void sign_differsWhenSecretDiffers() {
        String body = "same-body";
        assertThat(HmacUtil.sign("secret-A", body)).isNotEqualTo(HmacUtil.sign("secret-B", body));
    }

    @Test
    void verify_acceptsAGenuineSignature() {
        String secret = "endpoint-secret";
        String body = "{\"id\":\"abc\"}";
        assertThat(HmacUtil.verify(secret, body, HmacUtil.sign(secret, body))).isTrue();
    }

    @Test
    void verify_rejectsATamperedBody() {
        String secret = "endpoint-secret";
        String signature = HmacUtil.sign(secret, "{\"amount\":10}");
        // Same secret, but the body a merchant received was altered in flight.
        assertThat(HmacUtil.verify(secret, "{\"amount\":1000}", signature)).isFalse();
    }

    @Test
    void verify_rejectsWrongSecret() {
        String body = "{\"id\":\"abc\"}";
        String signature = HmacUtil.sign("real-secret", body);
        assertThat(HmacUtil.verify("attacker-guess", body, signature)).isFalse();
    }

    @Test
    void verify_rejectsNullSignatureHeader() {
        assertThat(HmacUtil.verify("secret", "body", null)).isFalse();
    }
}
