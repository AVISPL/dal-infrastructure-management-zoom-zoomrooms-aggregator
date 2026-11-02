package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Regression tests for issues found by the simulator-backed tests, with the Zoom API stubbed by overriding
 * doGet/doPost/doPatch.
 */
@Tag("Mock")
public class ZoomRoomsAggregatorRegressionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOM_ID = "room1";
    private static final int FIRST_PAGE_DEVICES = 30;
    private static final int SECOND_PAGE_DEVICES = 5;

    /**
     * A valid OAuth token is reused until half of its lifetime has elapsed, instead of requesting a new one per call.
     */
    @Test
    public void validTokenIsReusedAcrossCalls() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        ZoomRoomsAggregatorCommunicator communicator = new StubbedCommunicator() {
            @Override
            protected <Request, Response> Response doPost(String url, Request request, Class<Response> responseClass) throws Exception {
                tokenRequests.incrementAndGet();
                return super.doPost(url, request, responseClass);
            }
        };
        communicator.init();
        try {
            communicator.getMultipleStatistics();
            communicator.getMultipleStatistics();
            communicator.getMultipleStatistics();

            Assertions.assertEquals(1, tokenRequests.get(), "token requests for 3 calls with a token valid for 3599 s");
        } finally {
            communicator.destroy();
        }
    }

    /**
     * Room devices are read from every page (page_size + next_page_token query parameters), not only the first one.
     */
    @Test
    public void roomDevicesAreReadFromEveryPage() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new PaginatedDevicesCommunicator();
        communicator.init();
        try {
            Method method = ZoomRoomsAggregatorCommunicator.class.getDeclaredMethod("updateAndRetrieveRoomDevices", String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> deviceIds = (List<String>) method.invoke(communicator, ROOM_ID);

            Assertions.assertEquals(FIRST_PAGE_DEVICES + SECOND_PAGE_DEVICES, deviceIds.size());
        } finally {
            communicator.destroy();
        }
    }

    /**
     * Grouped room devices information (online / offline totals) counts the devices of every page.
     */
    @Test
    public void groupedRoomDevicesCountEveryPage() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new PaginatedDevicesCommunicator();
        communicator.init();
        try {
            aggregatedDevices(communicator).put(ROOM_ID, new AggregatedDevice());
            Map<String, String> properties = new HashMap<>();
            Method method = ZoomRoomsAggregatorCommunicator.class.getDeclaredMethod("retrieveGroupedRoomDevicesInformation", String.class, Map.class);
            method.setAccessible(true);
            method.invoke(communicator, ROOM_ID, properties);

            int onlineTotal = properties.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("#OnlineDevicesTotal"))
                    .mapToInt(entry -> Integer.parseInt(entry.getValue()))
                    .sum();
            Assertions.assertEquals(FIRST_PAGE_DEVICES + SECOND_PAGE_DEVICES, onlineTotal);
        } finally {
            communicator.destroy();
        }
    }

    /**
     * Account alert settings controls update the account settings of setting_type alert.
     */
    @Test
    public void accountAlertSettingControlUpdatesAlertSettings() throws Exception {
        List<String> patches = new CopyOnWriteArrayList<>();
        ZoomRoomsAggregatorCommunicator communicator = new StubbedCommunicator() {
            @Override
            protected <Request, Response> Response doPatch(String url, Request request, Class<Response> responseClass) throws Exception {
                patches.add(url + " " + JSON.writeValueAsString(request));
                return null;
            }
        };
        communicator.init();
        try {
            ControllableProperty controllableProperty = new ControllableProperty();
            controllableProperty.setDeviceId("aggregator");
            controllableProperty.setProperty("AccountAlertSettings#DetectCameraErrorAlert");
            controllableProperty.setValue(0);
            communicator.controlProperty(controllableProperty);

            Assertions.assertEquals(1, patches.size(), "PATCH requests: " + patches);
            Assertions.assertTrue(patches.get(0).startsWith("rooms/account_settings?setting_type=alert "), patches.get(0));
            Assertions.assertTrue(patches.get(0).contains("\"client_alert\":{\"detect_camera_error_alert\""), patches.get(0));
        } finally {
            communicator.destroy();
        }
    }

    /**
     * The statistics entry of an account settings switch holds the current value of the switch.
     */
    @Test
    public void accountSettingsStatisticsHoldTheSwitchValue() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new StubbedCommunicator();
        communicator.setDisplayAccountSettings(true);
        communicator.init();
        try {
            ExtendedStatistics statistics = (ExtendedStatistics) communicator.getMultipleStatistics().get(0);

            assertStatisticsMatchControls(statistics.getStatistics(), statistics.getControllableProperties());
            Assertions.assertEquals("true", statistics.getStatistics().get("AccountMeetingSettings#UpcomingMeetingAlert"));
            Assertions.assertEquals("true", statistics.getStatistics().get("AccountAlertSettings#DetectCameraErrorAlert"));
        } finally {
            communicator.destroy();
        }
    }

    /**
     * The statistics entry of a room settings switch holds the current value of the switch.
     */
    @Test
    public void roomSettingsStatisticsHoldTheSwitchValue() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new StubbedCommunicator();
        communicator.setDisplayRoomSettings(true);
        communicator.init();
        try {
            Map<String, String> properties = new HashMap<>();
            List<AdvancedControllableProperty> controls = new ArrayList<>();
            Method method = ZoomRoomsAggregatorCommunicator.class.getDeclaredMethod("populateRoomSettings", String.class, Map.class, List.class);
            method.setAccessible(true);
            method.invoke(communicator, ROOM_ID, properties, controls);

            assertStatisticsMatchControls(properties, controls);
            Assertions.assertEquals("true", properties.get("RoomControlsMeetingSettings#UpcomingMeetingAlert"));
            Assertions.assertEquals("true", properties.get("RoomControlsAlertSettings#DetectCameraErrorAlert"));
        } finally {
            communicator.destroy();
        }
    }

    private static void assertStatisticsMatchControls(Map<String, String> statistics, List<AdvancedControllableProperty> controls) {
        Assertions.assertFalse(controls.isEmpty(), "no controls reported");
        List<String> mismatches = controls.stream()
                .filter(control -> control.getType() instanceof AdvancedControllableProperty.Switch)
                .filter(control -> !String.valueOf(control.getValue()).equals(statistics.get(control.getName())))
                .map(control -> control.getName() + ": control " + control.getValue() + ", statistics " + statistics.get(control.getName()))
                .collect(Collectors.toList());
        Assertions.assertTrue(mismatches.isEmpty(), "statistics entries differ from their switch values: " + mismatches);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AggregatedDevice> aggregatedDevices(ZoomRoomsAggregatorCommunicator communicator) throws Exception {
        Field field = ZoomRoomsAggregatorCommunicator.class.getDeclaredField("aggregatedDevices");
        field.setAccessible(true);
        return (Map<String, AggregatedDevice>) field.get(communicator);
    }

    private static JsonNode settings(String url) throws IOException {
        if (url.contains("setting_type=meeting")) {
            return JSON.readTree("{\"zoom_rooms\": {\"upcoming_meeting_alert\": true, \"show_alert_before_meeting\": false}}");
        }
        return JSON.readTree("{\"client_alert\": {\"detect_camera_error_alert\": true, \"detect_speaker_error_alert\": false}}");
    }

    private static JsonNode devicesPage(int from, int count, String nextPageToken) throws IOException {
        String devices = IntStream.range(from, from + count)
                .mapToObj(i -> String.format("{\"id\": \"device%d\", \"room_name\": \"Room 1\", \"device_type\": \"Controller\", "
                        + "\"app_version\": \"1.0\", \"device_system\": \"iPad\", \"status\": \"Online\", \"serial_number\": \"SN%d\"}", i, i))
                .collect(Collectors.joining(","));
        return JSON.readTree(String.format("{\"page_size\": %d, \"next_page_token\": \"%s\", \"devices\": [%s]}", count, nextPageToken, devices));
    }

    /**
     * Communicator with configured credentials, a successful OAuth token request (valid for 3599 s) and settings
     * responses.
     */
    private static class StubbedCommunicator extends ZoomRoomsAggregatorCommunicator {
        StubbedCommunicator() throws IOException {
            setLogin("clientId");
            setPassword("clientSecret");
            setAccountId("accountId");
            setHost("api.zoom.us");
            setProtocol("https");
            setPort(443);
        }

        @Override
        protected <Request, Response> Response doPost(String url, Request request, Class<Response> responseClass) throws Exception {
            return JSON.readValue("{\"access_token\": \"token\", \"expires_in\": 3599}", responseClass);
        }

        @Override
        protected <Response> Response doGet(String url, Class<Response> responseClass) throws Exception {
            if (url.contains("settings")) {
                return responseClass.cast(settings(url));
            }
            throw new CommandFailureException("api.zoom.us", url, "Not found", 404);
        }
    }

    /**
     * Room devices served on two pages. Like the Zoom API, the list is only answered for a well-formed query string:
     * {@code ?page_size=N}, then {@code ?page_size=N&next_page_token=T}.
     */
    private static class PaginatedDevicesCommunicator extends StubbedCommunicator {
        PaginatedDevicesCommunicator() throws IOException {
        }

        @Override
        protected <Response> Response doGet(String url, Class<Response> responseClass) throws Exception {
            String devicesPath = "rooms/" + ROOM_ID + "/devices";
            if (url.matches(devicesPath + "\\?page_size=\\d+")) {
                return responseClass.cast(devicesPage(0, FIRST_PAGE_DEVICES, "page2"));
            }
            if (url.matches(devicesPath + "\\?page_size=\\d+&next_page_token=page2")) {
                return responseClass.cast(devicesPage(FIRST_PAGE_DEVICES, SECOND_PAGE_DEVICES, ""));
            }
            return super.doGet(url, responseClass);
        }
    }
}
