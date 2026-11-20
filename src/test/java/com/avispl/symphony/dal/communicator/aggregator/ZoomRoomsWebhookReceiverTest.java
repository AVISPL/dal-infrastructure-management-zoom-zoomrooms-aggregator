package com.avispl.symphony.dal.communicator.aggregator;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.webhook.WebhookReply;
import com.avispl.symphony.api.dal.webhook.WebhookRequest;

/**
 * Zoom webhook requests as the cloud host hands them over before queuing: signature (x-zm-signature over
 * "v0:{x-zm-request-timestamp}:{body}" with the app's secret token) and the endpoint.url_validation handshake.
 */
@Tag("Mock")
public class ZoomRoomsWebhookReceiverTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STARTED = "{\"event\":\"meeting.started\",\"event_ts\":1626230691572,\"payload\":{\"object\":{\"id\":\"1\",\"host_id\":\"u1\"}}}";

    private final String secret = UUID.randomUUID().toString();
    private ZoomRoomsAggregatorCommunicator communicator;

    @BeforeEach
    public void setUp() throws Exception {
        communicator = new ZoomRoomsAggregatorCommunicator();
        communicator.setWebhookSecretToken(secret);
    }

    @AfterEach
    public void tearDown() {
        communicator.destroy();
    }

    @Test
    public void receive_shouldAnswerUrlValidation_withPlainAndEncryptedToken_andNotDeliver() throws Exception {
        String plainToken = UUID.randomUUID().toString();
        String body = "{\"event\":\"endpoint.url_validation\",\"event_ts\":1654503849680,\"payload\":{\"plainToken\":\"" + plainToken + "\"}}";

        WebhookReply reply = communicator.receive(signed(body, nowSeconds()));

        Assertions.assertEquals(200, reply.getStatus());
        Assertions.assertFalse(reply.isDeliver());
        Assertions.assertEquals("application/json", reply.getContentType());
        JsonNode answer = JSON.readTree(reply.getBody());
        Assertions.assertEquals(plainToken, answer.get("plainToken").asText());
        Assertions.assertEquals(hmacHex(secret, plainToken), answer.get("encryptedToken").asText());
    }

    @Test
    public void receive_shouldAccept_whenSignatureMatches() throws Exception {
        WebhookReply reply = communicator.receive(signed(STARTED, nowSeconds()));

        Assertions.assertEquals(202, reply.getStatus());
        Assertions.assertTrue(reply.isDeliver());
    }

    @Test
    public void receive_shouldReject401_whenSignatureDiffers() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-zm-request-timestamp", nowSeconds());
        headers.put("x-zm-signature", "v0=" + hmacHex(UUID.randomUUID().toString(), "v0:" + headers.get("x-zm-request-timestamp") + ":" + STARTED));

        WebhookReply reply = communicator.receive(new WebhookRequest(headers, STARTED, new Date()));

        Assertions.assertEquals(401, reply.getStatus());
        Assertions.assertFalse(reply.isDeliver());
    }

    @Test
    public void receive_shouldReject401_whenTimestampIsOlderThanFiveMinutes() throws Exception {
        String sixMinutesAgo = String.valueOf(System.currentTimeMillis() / 1000 - 6 * 60);

        WebhookReply reply = communicator.receive(signed(STARTED, sixMinutesAgo));

        Assertions.assertEquals(401, reply.getStatus());
        Assertions.assertFalse(reply.isDeliver());
    }

    @Test
    public void receive_shouldAccept_whenTimestampIsInMilliseconds() throws Exception {
        WebhookReply reply = communicator.receive(signed(STARTED, String.valueOf(System.currentTimeMillis())));

        Assertions.assertTrue(reply.isDeliver());
    }

    @Test
    public void receive_shouldReject401_whenSignatureHeadersAreMissing() throws Exception {
        WebhookReply reply = communicator.receive(new WebhookRequest(new HashMap<>(), STARTED, new Date()));

        Assertions.assertEquals(401, reply.getStatus());
    }

    @Test
    public void receive_shouldReject401_whenNoSecretTokenIsConfigured() throws Exception {
        ZoomRoomsAggregatorCommunicator unconfigured = new ZoomRoomsAggregatorCommunicator();
        try {
            WebhookReply reply = unconfigured.receive(signed(STARTED, nowSeconds()));

            Assertions.assertEquals(401, reply.getStatus());
            Assertions.assertFalse(reply.isDeliver());
        } finally {
            unconfigured.destroy();
        }
    }

    private WebhookRequest signed(String body, String timestamp) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Zm-Request-Timestamp", timestamp);
        headers.put("X-Zm-Signature", "v0=" + hmacHex(secret, "v0:" + timestamp + ":" + body));
        return new WebhookRequest(headers, body, new Date());
    }

    private static String nowSeconds() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private static String hmacHex(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
