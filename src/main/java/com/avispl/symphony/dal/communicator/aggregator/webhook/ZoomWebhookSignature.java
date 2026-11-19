package com.avispl.symphony.dal.communicator.aggregator.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Zoom's webhook signature: {@code x-zm-signature = "v0=" + hex(HMAC-SHA256(secretToken, "v0:" + x-zm-request-timestamp + ":" + body))}.
 * Requests older than {@link #MAX_AGE} are refused, so a captured request cannot be replayed later.
 *
 * @author Symphony Dev Team
 * @since 1.2.10
 */
public final class ZoomWebhookSignature {

    /** How old a signed request may be. */
    public static final Duration MAX_AGE = Duration.ofMinutes(5);
    /** Timestamps at or above this are epoch milliseconds, below it epoch seconds. */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    private ZoomWebhookSignature() {
    }

    /**
     * Whether the request is signed with this secret and recent enough.
     *
     * @param timestamp {@code x-zm-request-timestamp}, epoch seconds or milliseconds
     */
    public static boolean valid(String secret, String timestamp, String body, String signature, Instant now) {
        if (secret == null || secret.isEmpty() || timestamp == null || signature == null) {
            return false;
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        // shortcut: Zoom documents the timestamp without a unit; both are accepted until a live request shows which one Zoom sends
        Instant sentAt = sent >= MILLIS_THRESHOLD ? Instant.ofEpochMilli(sent) : Instant.ofEpochSecond(sent);
        if (Duration.between(sentAt, now).abs().compareTo(MAX_AGE) > 0) {
            return false;
        }
        String expected = "v0=" + hmacHex(secret, "v0:" + timestamp.trim() + ":" + (body == null ? "" : body));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Lower-case hex of HMAC-SHA256 over {@code message}; also the {@code encryptedToken} of the endpoint.url_validation answer.
     */
    public static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
