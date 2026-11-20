package com.avispl.symphony.dal.communicator.aggregator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.webhook.WebhookEvent;
import com.avispl.symphony.dal.communicator.aggregator.webhook.ZoomWebhookEvent;

/**
 * Webhook support: meeting.started / meeting.ended set the room's EndpointStatistics inCall at once, and the value
 * holds over the metrics' room status until a metrics retrieval shows the API has caught up.
 */
@Tag("Mock")
public class ZoomRoomsWebhookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOM_ID = "rRoom1";
    private static final String ROOM_USER_ID = "uRoomUser1";
    /** Zoom's documented meeting.started example */
    private static final String DOCUMENTED_STARTED = "{\"event\":\"meeting.started\",\"event_ts\":1626230691572,\"payload\":{"
            + "\"account_id\":\"Wk9PTV9BQ0NPVU5UX0lE\",\"object\":{\"id\":\"1234567890\",\"uuid\":\"Wk9PTV9NRUVUSU5HX0lE\","
            + "\"host_id\":\"Wk9PTV9VU0VSX0lE\",\"topic\":\"My Meeting\",\"type\":8,\"start_time\":\"2021-07-13T21:44:51Z\","
            + "\"timezone\":\"America/Los_Angeles\",\"duration\":60}}}";

    private MetricsCommunicator communicator;
    private AggregatedDevice room;

    @BeforeEach
    public void setUp() throws Exception {
        communicator = new MetricsCommunicator();
        room = new AggregatedDevice();
        room.setDeviceId(ROOM_ID);
        room.setSerialNumber(ROOM_USER_ID);
        room.setDeviceOnline(true);
        room.setProperties(new HashMap<>());
        aggregatedDevices().put(ROOM_ID, room);
    }

    @AfterEach
    public void tearDown() {
        communicator.destroy();
    }

    @Test
    public void meetingStartedByRoomUserSetsInCall() throws Exception {
        communicator.digest(Collections.singletonList(event(1, "meeting.started", ROOM_USER_ID, System.currentTimeMillis())));

        Assertions.assertEquals(Boolean.TRUE, inCall());
        applyMetricsInCall(false); // metrics still say Available
        Assertions.assertEquals(Boolean.TRUE, inCall(), "webhook value wins over metrics not yet caught up");
    }

    @Test
    public void meetingEndedClearsInCall() throws Exception {
        long now = System.currentTimeMillis();
        communicator.digest(Arrays.asList(event(1, "meeting.started", ROOM_USER_ID, now - 1000), event(2, "meeting.ended", ROOM_USER_ID, now)));

        Assertions.assertEquals(Boolean.FALSE, inCall());
        applyMetricsInCall(true); // metrics still say InMeeting
        Assertions.assertEquals(Boolean.FALSE, inCall());
    }

    @Test
    public void redeliveryAndOlderEventsAreSkipped() throws Exception {
        long now = System.currentTimeMillis();
        communicator.digest(Collections.singletonList(event(5, "meeting.ended", ROOM_USER_ID, now)));
        // redelivery of an earlier sequence
        communicator.digest(Collections.singletonList(event(4, "meeting.started", ROOM_USER_ID, now + 5000)));
        Assertions.assertEquals(Boolean.FALSE, inCall());
        // new sequence, but the vendor event is older than the last one applied (out-of-order delivery)
        communicator.digest(Collections.singletonList(event(6, "meeting.started", ROOM_USER_ID, now - 5000)));
        Assertions.assertEquals(Boolean.FALSE, inCall());
    }

    @Test
    public void unknownRoomBadBodyAndOtherEventsDoNotFailTheBatch() throws Exception {
        WebhookEvent garbage = new WebhookEvent(1, new Date(), "not json");
        WebhookEvent other = new WebhookEvent(2, new Date(), "{\"event\":\"meeting.participant_joined\",\"payload\":{\"object\":{}}}");
        WebhookEvent validation = new WebhookEvent(3, new Date(), "{\"event\":\"endpoint.url_validation\",\"payload\":{\"plainToken\":\"x\"}}");
        WebhookEvent unknownRoom = event(4, "meeting.started", "someoneElse", System.currentTimeMillis());
        WebhookEvent ours = event(5, "meeting.started", ROOM_USER_ID, System.currentTimeMillis());

        communicator.digest(Arrays.asList(garbage, other, validation, unknownRoom, ours));

        Assertions.assertEquals(Boolean.TRUE, inCall());
    }

    @Test
    public void webhookSetsInCallEvenWithRoomsMetricsOff() throws Exception {
        communicator.setIncludeRoomsMetrics(false);
        communicator.digest(Collections.singletonList(event(1, "meeting.started", ROOM_USER_ID, System.currentTimeMillis())));
        Assertions.assertEquals(Boolean.TRUE, inCall());
    }

    @Test
    public void metricsApplyAgainOnceSettled() throws Exception {
        setField("serviceRunning", true);
        long settled = System.currentTimeMillis() - ZoomRoomsAggregatorCommunicator.WEBHOOK_OVERRIDE_SETTLE_MS - 1000;
        communicator.digest(Collections.singletonList(event(1, "meeting.started", ROOM_USER_ID, settled)));

        invoke("retrieveZoomRoomMetrics");
        applyMetricsInCall(false);

        Assertions.assertEquals(Boolean.FALSE, inCall(), "metrics retrieved >= settle time after the event apply again");
    }

    @Test
    public void recentWebhookValueSurvivesMetricsRetrieval() throws Exception {
        setField("serviceRunning", true);
        communicator.digest(Collections.singletonList(event(1, "meeting.started", ROOM_USER_ID, System.currentTimeMillis())));

        invoke("retrieveZoomRoomMetrics");
        applyMetricsInCall(false);

        Assertions.assertEquals(Boolean.TRUE, inCall());
    }

    @Test
    public void digest_shouldSetInCall_whenRoomUserJoinsAnotherHostsMeeting() throws Exception {
        communicator.digest(Collections.singletonList(participant(1, "meeting.participant_joined", "m42", "someHost", ROOM_USER_ID, System.currentTimeMillis())));

        Assertions.assertEquals(Boolean.TRUE, inCall());
    }

    @Test
    public void digest_shouldClearInCall_whenRoomUserLeaves() throws Exception {
        long now = System.currentTimeMillis();
        communicator.digest(Arrays.asList(participant(1, "meeting.participant_joined", "m42", "someHost", ROOM_USER_ID, now - 1000),
                participant(2, "meeting.participant_left", "m42", "someHost", ROOM_USER_ID, now)));

        Assertions.assertEquals(Boolean.FALSE, inCall());
    }

    @Test
    public void digest_shouldClearInCall_whenJoinedMeetingEnds() throws Exception {
        long now = System.currentTimeMillis();
        communicator.digest(Arrays.asList(participant(1, "meeting.participant_joined", "m42", "someHost", ROOM_USER_ID, now - 1000),
                meeting(2, "meeting.ended", "m42", "someHost", now)));

        Assertions.assertEquals(Boolean.FALSE, inCall());
    }

    @Test
    public void digest_shouldKeepInCall_whenAnotherMeetingEnds() throws Exception {
        long now = System.currentTimeMillis();
        communicator.digest(Arrays.asList(participant(1, "meeting.participant_joined", "m42", "someHost", ROOM_USER_ID, now - 1000),
                meeting(2, "meeting.ended", "m77", "someHost", now)));

        Assertions.assertEquals(Boolean.TRUE, inCall());
    }

    @Test
    public void digest_shouldIgnoreParticipant_whenUserIsNoRoom() throws Exception {
        communicator.digest(Collections.singletonList(participant(1, "meeting.participant_joined", "m42", "someHost", "aPerson", System.currentTimeMillis())));

        Assertions.assertNull(room.getMonitoredStatistics(), "participants are matched against room user ids only");
    }

    @Test
    public void digest_shouldMatchParticipantById_whenParticipantUserIdIsAbsent() throws Exception {
        String body = "{\"event\":\"meeting.participant_joined\",\"event_ts\":" + System.currentTimeMillis() + ",\"payload\":{\"object\":"
                + "{\"id\":\"m42\",\"host_id\":\"someHost\",\"participant\":{\"id\":\"" + ROOM_USER_ID + "\",\"user_name\":\"Board Room\"}}}}";

        communicator.digest(Collections.singletonList(new WebhookEvent(1, new Date(), body)));

        Assertions.assertEquals(Boolean.TRUE, inCall());
    }

    private static WebhookEvent participant(long sequence, String type, String meetingId, String hostId, String participantUserId, long eventTs) {
        String body = "{\"event\":\"" + type + "\",\"event_ts\":" + eventTs + ",\"payload\":{\"object\":{\"id\":\"" + meetingId
                + "\",\"host_id\":\"" + hostId + "\",\"participant\":{\"participant_user_id\":\"" + participantUserId
                + "\",\"user_name\":\"someone\"}}}}";
        return new WebhookEvent(sequence, new Date(), body);
    }

    private static WebhookEvent meeting(long sequence, String type, String meetingId, String hostId, long eventTs) {
        String body = "{\"event\":\"" + type + "\",\"event_ts\":" + eventTs + ",\"payload\":{\"object\":{\"id\":\"" + meetingId
                + "\",\"host_id\":\"" + hostId + "\"}}}";
        return new WebhookEvent(sequence, new Date(), body);
    }

    @Test
    public void parsesDocumentedPayload() {
        ZoomWebhookEvent parsed = ZoomWebhookEvent.parse(DOCUMENTED_STARTED);
        Assertions.assertEquals(ZoomWebhookEvent.Type.MEETING_STARTED, parsed.getType());
        Assertions.assertEquals("Wk9PTV9VU0VSX0lE", parsed.getHostId());
        Assertions.assertEquals("1234567890", parsed.getMeetingId());
        Assertions.assertEquals(1626230691572L, parsed.getOccurredAt().toEpochMilli());

        ZoomWebhookEvent ended = ZoomWebhookEvent.parse(DOCUMENTED_STARTED.replace("meeting.started", "meeting.ended"));
        Assertions.assertEquals(ZoomWebhookEvent.Type.MEETING_ENDED, ended.getType());
    }

    @Test
    public void documentedPayloadHostedByRoomUserSetsInCall() throws Exception {
        room.setSerialNumber("Wk9PTV9VU0VSX0lE");
        communicator.digest(Collections.singletonList(new WebhookEvent(1, new Date(), DOCUMENTED_STARTED)));
        Assertions.assertEquals(Boolean.TRUE, inCall());
        communicator.digest(Collections.singletonList(new WebhookEvent(2, new Date(),
                DOCUMENTED_STARTED.replace("meeting.started", "meeting.ended").replace("1626230691572", "1626234291572"))));
        Assertions.assertEquals(Boolean.FALSE, inCall());
    }

    @Test
    public void roomIdAsHostIdDoesNotMatch() throws Exception {
        communicator.digest(Collections.singletonList(event(1, "meeting.started", ROOM_ID, System.currentTimeMillis())));
        Assertions.assertNull(room.getMonitoredStatistics(), "host_id is matched against room user ids only");
    }

    // ---- helpers

    private static WebhookEvent event(long sequence, String type, String hostId, long eventTs) {
        String body = String.format("{\"event\":\"%s\",\"event_ts\":%d,\"payload\":{\"account_id\":\"acc\",\"object\":{\"id\":\"850%d\",\"host_id\":\"%s\"}}}",
                type, eventTs, sequence, hostId);
        return new WebhookEvent(sequence, new Date(), body);
    }

    private Boolean inCall() {
        List<Statistics> statistics = room.getMonitoredStatistics();
        Assertions.assertNotNull(statistics, "monitored statistics set");
        return statistics.stream().filter(s -> s instanceof EndpointStatistics).map(s -> ((EndpointStatistics) s).isInCall()).findFirst()
                .orElseThrow(() -> new AssertionError("no EndpointStatistics"));
    }

    private void applyMetricsInCall(boolean metricsInCall) throws Exception {
        Method m = ZoomRoomsAggregatorCommunicator.class.getDeclaredMethod("applyRoomInCall", AggregatedDevice.class, boolean.class);
        m.setAccessible(true);
        m.invoke(communicator, room, metricsInCall);
    }

    private void invoke(String method) throws Exception {
        Method m = ZoomRoomsAggregatorCommunicator.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(communicator);
    }

    @SuppressWarnings("unchecked")
    private Map<String, AggregatedDevice> aggregatedDevices() throws Exception {
        Field f = ZoomRoomsAggregatorCommunicator.class.getDeclaredField("aggregatedDevices");
        f.setAccessible(true);
        return (Map<String, AggregatedDevice>) f.get(communicator);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ZoomRoomsAggregatorCommunicator.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(communicator, value);
    }

    /** Answers the rooms metrics list with the room Available. */
    private static class MetricsCommunicator extends ZoomRoomsAggregatorCommunicator {
        MetricsCommunicator() throws IOException {
        }

        @Override
        protected <Response> Response doGet(String url, Class<Response> responseClass) throws Exception {
            if (url.startsWith("metrics/zoomrooms?")) {
                return responseClass.cast(JSON.readTree("{\"zoom_rooms\":[{\"id\":\"" + ROOM_ID + "\",\"status\":\"Available\"}],\"next_page_token\":\"\"}"));
            }
            throw new IllegalArgumentException("unexpected GET " + url);
        }
    }
}
