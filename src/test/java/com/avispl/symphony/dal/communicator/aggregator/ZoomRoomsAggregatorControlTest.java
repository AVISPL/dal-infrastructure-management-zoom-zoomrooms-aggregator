package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Room and account settings controls, without Zoom API access: PATCH requests are captured by overriding doPatch.
 *
 * SYAL-2316, SYAL-2317, SYAL-2896
 */
@Tag("Mock")
public class ZoomRoomsAggregatorControlTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HideShareInstruction was exposed as a room control but could not be resolved to a setting. */
    @Test
    public void hideShareInstructionUpdatesRoomMeetingSetting() throws Exception {
        PatchCapturingCommunicator communicator = new PatchCapturingCommunicator();
        communicator.controlProperty(control("room1", "RoomControlsMeetingSettings#HideShareInstruction", 1));

        Assertions.assertEquals(1, communicator.patches.size());
        Assertions.assertEquals("rooms/room1/settings?setting_type=meeting", communicator.patches.get(0).url);
        Assertions.assertEquals("{\"zoom_rooms\":{\"hide_share_instruction\":\"true\"}}", communicator.patches.get(0).body.toString());
    }

    /** Account settings are aggregator level controls: they must be applied whatever device id Symphony passes. */
    @Test
    public void accountSettingIsUpdatedWithoutDeviceId() throws Exception {
        PatchCapturingCommunicator communicator = new PatchCapturingCommunicator();
        communicator.controlProperty(control(null, "AccountMeetingSettings#DisplayTopBanner", 0));
        communicator.controlProperty(control("aggregator", "AccountAlertSettings#DetectCameraErrorAlert", 1));

        Assertions.assertEquals(2, communicator.patches.size());
        Assertions.assertEquals("rooms/account_settings?setting_type=meeting", communicator.patches.get(0).url);
        Assertions.assertEquals("{\"zoom_rooms\":{\"display_top_banner\":\"false\"}}", communicator.patches.get(0).body.toString());
        Assertions.assertEquals("rooms/account_settings?setting_type=alert", communicator.patches.get(1).url);
        Assertions.assertEquals("{\"client_alert\":{\"detect_camera_error_alert\":\"true\"}}", communicator.patches.get(1).body.toString());
    }

    /** Zoom renamed zoom_room_come_back_online to zoom_room_re_online and ignores the old name. */
    @Test
    public void zoomRoomComeBackOnlineWritesZoomRoomReOnline() throws Exception {
        PatchCapturingCommunicator communicator = new PatchCapturingCommunicator();
        communicator.controlProperty(control("room1", "RoomControlsAlertSettings#ZoomRoomComeBackOnline", 1));

        JsonNode notification = communicator.patches.get(0).body.path("notification");
        Assertions.assertEquals("true", notification.path("zoom_room_re_online").asText());
        Assertions.assertFalse(notification.has("zoom_room_come_back_online"));
    }

    /** Zoom answers 429 on quick successive updates: the update is retried. */
    @Test
    public void rateLimitedUpdateIsRetried() throws Exception {
        PatchCapturingCommunicator communicator = new PatchCapturingCommunicator();
        communicator.rateLimitedResponses = 2;
        communicator.controlProperty(control("room1", "RoomControlsMeetingSettings#AutoStartScheduledMeeting", 1));

        Assertions.assertEquals(3, communicator.attempts);
        Assertions.assertEquals(1, communicator.patches.size());
    }

    /** An unknown setting is reported by its property name, nothing is sent. */
    @Test
    public void unknownSettingIsReportedByName() throws Exception {
        PatchCapturingCommunicator communicator = new PatchCapturingCommunicator();
        IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> communicator.controlProperty(control("room1", "RoomControlsMeetingSettings#Unknown", 1)));

        Assertions.assertEquals("Unsupported control property: RoomControlsMeetingSettings#Unknown", error.getMessage());
        Assertions.assertEquals(0, communicator.attempts);
    }

    /** The control value is read from zoom_room_re_online. */
    @Test
    public void zoomRoomComeBackOnlineIsReadFromZoomRoomReOnline() throws Exception {
        Assertions.assertEquals("false", comeBackOnlineControlValue("{\"notification\": {\"zoom_room_re_online\": false}}"));
        Assertions.assertEquals("true", comeBackOnlineControlValue("{\"notification\": {\"zoom_room_re_online\": true}}"));
    }

    private static String comeBackOnlineControlValue(String alertSettings) throws Exception {
        PatchCapturingCommunicator communicator = new PatchCapturingCommunicator() {
            @Override
            protected <Response> Response doGet(String url, Class<Response> responseClass) throws Exception {
                if (url.contains("rooms/account_settings")) {
                    return responseClass.cast(MAPPER.readTree(url.endsWith("alert") ? alertSettings : "{\"zoom_rooms\": {}}"));
                }
                throw new IllegalStateException("Unexpected request: " + url);
            }
        };
        communicator.setDisplayAccountSettings(true);
        communicator.init();
        try {
            ExtendedStatistics statistics = (ExtendedStatistics) communicator.getMultipleStatistics().get(0);
            return statistics.getControllableProperties().stream()
                    .filter(control -> "AccountAlertSettings#ZoomRoomComeBackOnline".equals(control.getName()))
                    .map(control -> String.valueOf(control.getValue()))
                    .findFirst()
                    .orElse(null);
        } finally {
            communicator.destroy();
        }
    }

    private static ControllableProperty control(String deviceId, String property, Object value) {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setDeviceId(deviceId);
        controllableProperty.setProperty(property);
        controllableProperty.setValue(value);
        return controllableProperty;
    }

    private static class Patch {
        final String url;
        final JsonNode body;

        Patch(String url, JsonNode body) {
            this.url = url;
            this.body = body;
        }
    }

    /**
     * Communicator with a successful OAuth token request that records PATCH requests instead of sending them.
     */
    private static class PatchCapturingCommunicator extends ZoomRoomsAggregatorCommunicator {
        final List<Patch> patches = new ArrayList<>();
        int rateLimitedResponses;
        int attempts;

        PatchCapturingCommunicator() throws IOException {
            setLogin("clientId");
            setPassword("clientSecret");
            setAccountId("accountId");
            setHost("api.zoom.us");
            setProtocol("https");
            setPort(443);
        }

        @Override
        protected <Request, Response> Response doPost(String url, Request request, Class<Response> responseClass) throws Exception {
            return MAPPER.readValue("{\"access_token\": \"token\", \"expires_in\": 3599}", responseClass);
        }

        @Override
        protected <Request, Response> Response doPatch(String url, Request request, Class<Response> responseClass) throws Exception {
            attempts++;
            if (rateLimitedResponses > 0) {
                rateLimitedResponses--;
                throw new CommandFailureException("api.zoom.us", url, "Simulated rate limit", 429);
            }
            patches.add(new Patch(url, MAPPER.valueToTree(request)));
            return null;
        }
    }
}
