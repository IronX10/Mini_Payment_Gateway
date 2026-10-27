package com.dixansh.mini_payment_gateway.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC-SHA256 for webhook signing. Every delivery carries {@code X-Signature: sha256=<hex>} where
 * {@code <hex>} is {@code HMAC-SHA256(endpoint.secret, requestBody)}. HMAC (keyed) rather than a
 * plain hash so a third party who sees the body still can't forge the signature without the secret.
 */
public final class HmacUtil {

    private static final String ALGO = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private HmacUtil() {
    }

    /** Returns {@code sha256=<hexdigest>}, the value clients put in (and merchants verify from) X-Signature. */
    public static String sign(String secret, String body) {
        return PREFIX + hexHmac(secret, body);
    }

    /**
     * Constant-time verification of an {@code X-Signature} header against a freshly computed HMAC.
     * A naive {@code equals} leaks timing information that can be exploited to guess the signature
     * byte by byte, so we compare with {@link MessageDigest#isEqual}.
     */
    public static boolean verify(String secret, String body, String signatureHeader) {
        if (signatureHeader == null) {
            return false;
        }
        String expected = sign(secret, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexHmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // HmacSHA256 is guaranteed present on every JVM; a non-empty key never trips init.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
