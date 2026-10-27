package com.dixansh.mini_payment_gateway.util;

import com.dixansh.mini_payment_gateway.dto.PaymentResponse;

/**
 * Minimal, dependency-free JSON writer for the one shape we persist: {@link PaymentResponse}.
 *
 * <p>Why hand-rolled instead of Jackson? The stored {@code response_body} is replayed verbatim on
 * an idempotent retry, so it must be stable and self-contained. Building it here keeps the
 * persisted format decoupled from whichever Jackson version the web layer happens to use.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * Wraps an already-serialized payload into the event envelope that is both the Kafka message
     * value and the exact webhook body. {@code rawData} is spliced in verbatim (it is already valid
     * JSON), so the merchant-facing body stays byte-stable regardless of the web layer's serializer.
     * Shape: {@code {"id","type","aggregateId","occurredAt","data":<rawData>}}.
     */
    public static String envelope(String id, String type, String aggregateId,
                                  String occurredAt, String rawData) {
        StringBuilder sb = new StringBuilder(rawData.length() + 160);
        sb.append('{');
        field(sb, "id", id).append(',');
        field(sb, "type", type).append(',');
        field(sb, "aggregateId", aggregateId).append(',');
        field(sb, "occurredAt", occurredAt).append(',');
        sb.append("\"data\":").append(rawData == null ? "null" : rawData);
        sb.append('}');
        return sb.toString();
    }

    public static String toJson(PaymentResponse r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "id", r.id()).append(',');
        field(sb, "status", r.status()).append(',');
        numField(sb, "source", r.source()).append(',');
        numField(sb, "dest", r.dest()).append(',');
        sb.append("\"amount\":").append(r.amount() == null ? "null" : r.amount().toPlainString()).append(',');
        field(sb, "currency", r.currency()).append(',');
        field(sb, "failureReason", r.failureReason()).append(',');
        field(sb, "createdAt", r.createdAt() == null ? null : r.createdAt().toString()).append(',');
        field(sb, "updatedAt", r.updatedAt() == null ? null : r.updatedAt().toString());
        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        return sb;
    }

    private static StringBuilder numField(StringBuilder sb, String key, Object value) {
        sb.append('"').append(key).append("\":").append(value == null ? "null" : value.toString());
        return sb;
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
