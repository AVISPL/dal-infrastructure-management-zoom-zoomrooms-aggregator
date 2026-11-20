/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.webhook;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One Zoom webhook delivery, parsed.
 * <p>
 * Format as documented by Zoom (meeting.ended is the same, with the same {@code object} fields):
 *
 * <pre>
 * {"event":"meeting.started","event_ts":1626230691572,"payload":{"account_id":"...","object":{"id":"1234567890",
 *  "uuid":"...","host_id":"...","topic":"My Meeting","type":8,"start_time":"2021-07-13T21:44:51Z",
 *  "timezone":"America/Los_Angeles","duration":60}}}
 * </pre>
 *
 * The event names no room: the adapter matches {@code object.host_id} against the rooms' user ids, so only meetings
 * hosted by a room's own user are attributed to that room.
 * <p>
 * {@code endpoint.url_validation} (Zoom's URL challenge) is recognised but cannot be answered here: Zoom expects the
 * HMAC response in the HTTP reply to its POST, which only the receiving service (dal-webhook) can give.
 * <p>
 * Bodies are untrusted internet input: parsed as a plain JSON tree only (no type information, no binding to classes).
 */
public final class ZoomWebhookEvent {

    public enum Type {
        MEETING_STARTED, MEETING_ENDED, PARTICIPANT_JOINED, PARTICIPANT_LEFT, URL_VALIDATION, UNKNOWN
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Type type;
    private final String rawType;
    /** {@code object.host_id}: the meeting host's user id, or {@code null} */
    private final String hostId;
    private final String meetingId;
    /** {@code object.participant}'s user id for participant events, or {@code null} */
    private final String participantUserId;
    /** vendor event time, or {@code null} when absent / unparseable */
    private final Instant occurredAt;

    private ZoomWebhookEvent(Type type, String rawType, String hostId, String meetingId, String participantUserId, Instant occurredAt) {
        this.type = type;
        this.rawType = rawType;
        this.hostId = hostId;
        this.meetingId = meetingId;
        this.participantUserId = participantUserId;
        this.occurredAt = occurredAt;
    }

    /**
     * @param body raw delivery body
     * @return the parsed event
     * @throws IllegalArgumentException if the body is not a JSON object
     */
    public static ZoomWebhookEvent parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        String rawType = text(root, "event");
        Type type;
        if ("meeting.started".equals(rawType)) {
            type = Type.MEETING_STARTED;
        } else if ("meeting.ended".equals(rawType)) {
            type = Type.MEETING_ENDED;
        } else if ("meeting.participant_joined".equals(rawType)) {
            type = Type.PARTICIPANT_JOINED;
        } else if ("meeting.participant_left".equals(rawType)) {
            type = Type.PARTICIPANT_LEFT;
        } else if ("endpoint.url_validation".equals(rawType)) {
            type = Type.URL_VALIDATION;
        } else {
            type = Type.UNKNOWN;
        }
        JsonNode object = root.path("payload").path("object");

        Instant occurredAt = null;
        JsonNode ts = root.get("event_ts");
        if (ts != null && ts.canConvertToLong()) {
            occurredAt = Instant.ofEpochMilli(ts.asLong());
        }
        if (occurredAt == null) {
            occurredAt = instant(text(object, type == Type.MEETING_ENDED ? "end_time" : "start_time"));
        }
        JsonNode participant = object.path("participant");
        // shortcut: participant_user_id, else id, per Zoom's docs; confirm against a live event which one carries the room's user id
        String participantUserId = text(participant, "participant_user_id");
        if (participantUserId == null || participantUserId.isEmpty()) {
            participantUserId = text(participant, "id");
        }
        return new ZoomWebhookEvent(type, rawType, text(object, "host_id"), text(object, "id"), participantUserId, occurredAt);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    private static Instant instant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public Type getType() {
        return type;
    }

    public String getRawType() {
        return rawType;
    }

    public String getHostId() {
        return hostId;
    }

    public String getMeetingId() {
        return meetingId;
    }

    public String getParticipantUserId() {
        return participantUserId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
