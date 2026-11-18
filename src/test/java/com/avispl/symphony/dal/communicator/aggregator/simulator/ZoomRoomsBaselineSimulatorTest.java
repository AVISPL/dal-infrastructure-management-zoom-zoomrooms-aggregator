/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * One collection of the initial fleet (10 rooms x 100 devices): identities, statistics, metrics, user details,
 * account settings and live meetings, compared with the simulator state.
 */
public class ZoomRoomsBaselineSimulatorTest extends ZoomRoomsSimulatorTestBase {

    @Test
    public void oneCycleReportsEveryRoomAndRoomDeviceAsTheSimulatorHasThem() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        List<AggregatedDevice> devices = waitForFullCollection(communicator);
        // Live meeting details follow the room details in the same cycle; give it the next poll.
        devices = pollUntil(communicator, "live meetings of the InMeeting rooms", ONE_INTERVAL_WAIT_MS, list -> {
            for (AggregatedDevice room : rooms(list)) {
                if ("InMeeting".equals(room.getProperties().get("Metrics#RoomStatus")) && !room.getProperties().containsKey("LiveMeeting#Topic")) {
                    return false;
                }
            }
            return true;
        });
        JsonNode state = state();
        List<String> failures = new ArrayList<>();

        Assertions.assertEquals(state.path("rooms").size(), rooms(devices).size(), "rooms reported");
        int deviceTotal = 0;
        for (JsonNode simRoom : state.path("rooms")) {
            deviceTotal += simRoom.path("devices").size();
            String roomId = simRoom.path("room_id").asText();
            String userId = simRoom.path("id").asText();
            AggregatedDevice room = byId(devices, roomId);
            if (room == null) {
                failures.add("room " + roomId + " not reported (deviceId must be room_id)");
                continue;
            }
            Map<String, String> p = room.getProperties();
            String status = simRoom.path("status").asText();
            check(failures, roomId + " serialNumber (room id / user id)", userId, room.getSerialNumber());
            check(failures, roomId + " deviceName", simRoom.path("name").asText(), room.getDeviceName());
            check(failures, roomId + " deviceOnline", !"Offline".equals(status), room.getDeviceOnline());
            check(failures, roomId + " Metrics#RoomStatus", status, p.get("Metrics#RoomStatus"));
            JsonNode metrics = simRoom.path("metrics");
            check(failures, roomId + " Metrics#Email", metrics.path("email").asText(), p.get("Metrics#Email"));
            check(failures, roomId + " Metrics#CalendarName", metrics.path("calendar_name").asText(), p.get("Metrics#CalendarName"));
            check(failures, roomId + " Metrics#Camera", metrics.path("camera").asText(), p.get("Metrics#Camera"));
            check(failures, roomId + " Metrics#Health", metrics.path("health").asText(), p.get("Metrics#Health"));
            check(failures, roomId + " Metrics#DeviceIp", metrics.path("device_ip").asText(), p.get("Metrics#DeviceIp"));
            check(failures, roomId + " Metrics#LastStartTime", metrics.path("last_start_time").asText(), p.get("Metrics#LastStartTime"));
            if (!p.containsKey("Metrics#Uptime(min)")) {
                failures.add(roomId + " Metrics#Uptime(min) missing");
            }
            checkRecentTimestamp(failures, roomId + " Metrics#DataRetrievedTime", p.get("Metrics#DataRetrievedTime"));
            if ("Offline".equals(status)) {
                check(failures, roomId + " RoomStatus#ZoomRoom (issue 'Zoom room is offline')", "OFFLINE", p.get("RoomStatus#ZoomRoom"));
            }

            JsonNode user = user(userId);
            check(failures, roomId + " RoomUserDetails#Email", user.path("email").asText(), p.get("RoomUserDetails#Email"));
            check(failures, roomId + " RoomUserDetails#Timezone", user.path("timezone").asText(), p.get("RoomUserDetails#Timezone"));
            check(failures, roomId + " RoomUserDetails#RoleName", user.path("role_name").asText(), p.get("RoomUserDetails#RoleName"));

            // Grouped room devices: online / offline totals per type
            Map<String, int[]> totals = new HashMap<>();
            for (JsonNode simDevice : simRoom.path("devices")) {
                int[] counts = totals.computeIfAbsent(simDevice.path("device_type").asText().replace(" ", ""), k -> new int[2]);
                counts["Online".equals(simDevice.path("status").asText()) ? 0 : 1]++;
            }
            for (Map.Entry<String, int[]> entry : totals.entrySet()) {
                check(failures, roomId + " RoomDevices" + entry.getKey() + "s#OnlineDevicesTotal", String.valueOf(entry.getValue()[0]),
                        p.get("RoomDevices" + entry.getKey() + "s#OnlineDevicesTotal"));
                check(failures, roomId + " RoomDevices" + entry.getKey() + "s#OfflineDevicesTotal", String.valueOf(entry.getValue()[1]),
                        p.get("RoomDevices" + entry.getKey() + "s#OfflineDevicesTotal"));
            }

            // Room settings: every switch the adapter shows equals the simulator's value
            Map<String, JsonNode> simSettings = flatten(simRoom.path("settings"));
            int settingControls = 0;
            for (AdvancedControllableProperty control : room.getControllableProperties()) {
                if (control.getType() instanceof AdvancedControllableProperty.Switch) {
                    settingControls++;
                    if (!p.containsKey(control.getName())) {
                        failures.add(roomId + " control " + control.getName() + " has no statistics entry");
                    }
                }
            }
            if (settingControls == 0) {
                failures.add(roomId + " no room settings controls");
            }
            checkSwitch(failures, roomId, room.getControllableProperties(), p, "RoomControlsMeetingSettings#UpcomingMeetingAlert",
                    simSettings.get("meeting.zoom_rooms.upcoming_meeting_alert").asBoolean());
            checkSwitch(failures, roomId, room.getControllableProperties(), p, "RoomControlsMeetingSettings#ShowAlertBeforeMeeting",
                    simSettings.get("meeting.zoom_rooms.show_alert_before_meeting").asBoolean());
            checkSwitch(failures, roomId, room.getControllableProperties(), p, "RoomControlsAlertSettings#ZoomRoomOffline",
                    simSettings.get("alert.notification.zoom_room_offline").asBoolean());
            // battery_is_charging is not in the room alert settings of the live API (simulator aligned with it): its
            // control is not shown, so a battery setting the API has is checked instead
            checkSwitch(failures, roomId, room.getControllableProperties(), p, "RoomControlsAlertSettings#BatteryLowAndNotCharging",
                    simSettings.get("alert.notification.battery_low_and_not_charging").asBoolean());

            // Live meeting and call status
            boolean inMeeting = "InMeeting".equals(status);
            check(failures, roomId + " inCall", inMeeting, inCall(room));
            if (inMeeting) {
                JsonNode live = simRoom.path("liveMeeting");
                check(failures, roomId + " LiveMeeting#Topic", live.path("topic").asText(), p.get("LiveMeeting#Topic"));
                check(failures, roomId + " LiveMeeting#ID", live.path("id").asText(), p.get("LiveMeeting#ID"));
                check(failures, roomId + " LiveMeeting#UUID", live.path("uuid").asText(), p.get("LiveMeeting#UUID"));
                check(failures, roomId + " LiveMeeting#Host", live.path("host").asText(), p.get("LiveMeeting#Host"));
                check(failures, roomId + " LiveMeeting#Participants", live.path("participants").asText(), p.get("LiveMeeting#Participants"));
                check(failures, roomId + " LiveMeeting#StartTime", live.path("start_time").asText(), p.get("LiveMeeting#StartTime"));
            } else if (p.keySet().stream().anyMatch(key -> key.startsWith("LiveMeeting#"))) {
                failures.add(roomId + " has LiveMeeting# properties but is " + status);
            }

            // Room devices: identity, MACs, details
            int missing = 0;
            for (JsonNode simDevice : simRoom.path("devices")) {
                String deviceId = simDevice.path("id").asText();
                AggregatedDevice device = byId(devices, deviceId);
                if (device == null) {
                    missing++;
                    continue;
                }
                Map<String, String> dp = device.getProperties();
                check(failures, deviceId + " serialNumber", simDevice.path("serial_number").asText(), device.getSerialNumber());
                check(failures, deviceId + " ZoomRoomId", roomId, dp.get("ZoomRoomId"));
                check(failures, deviceId + " deviceName", simRoom.path("name").asText() + ": " + simDevice.path("device_hostname").asText(), device.getDeviceName());
                check(failures, deviceId + " deviceModel", simDevice.path("device_model").asText(), device.getDeviceModel());
                check(failures, deviceId + " deviceOnline", "Online".equals(simDevice.path("status").asText()), device.getDeviceOnline());
                check(failures, deviceId + " IPAddress", simDevice.path("ip_address").asText(), dp.get("IPAddress"));
                check(failures, deviceId + " Firmware", simDevice.path("device_firmware").asText(), dp.get("Firmware"));
                check(failures, deviceId + " AppVersion", simDevice.path("app_version").asText(), dp.get("AppVersion"));
                check(failures, deviceId + " System", simDevice.path("device_system").asText(), dp.get("System"));
                List<String> macs = new ArrayList<>();
                for (JsonNode mac : simDevice.path("device_mac_addresses")) {
                    macs.add(mac.asText().replace('-', ':').toUpperCase());
                }
                check(failures, deviceId + " macAddresses", macs, device.getMacAddresses());
            }
            if (missing > 0) {
                failures.add(roomId + ": " + missing + " of " + simRoom.path("devices").size() + " room devices not reported");
            }
        }
        if (deviceTotal != roomDevices(devices).size()) {
            failures.add(0, "room devices reported: expected " + deviceTotal + " but was " + roomDevices(devices).size()
                    + ". Room device requests in the simulator log (a second page needs ?page_size and ?next_page_token):"
                    + requestEvidence("/devices", 2) + requestEvidence("/devices&", 3));
        }

        // Aggregator: account settings, rate limit remainder, metadata, no errors
        ExtendedStatistics aggregator = aggregatorStatistics(communicator);
        Map<String, String> stats = aggregator.getStatistics();
        Map<String, JsonNode> accountSettings = flatten(state().path("accountSettings"));
        checkSwitch(failures, "aggregator", aggregator.getControllableProperties(), stats, "AccountMeetingSettings#WeeklySystemRestart",
                accountSettings.get("meeting.zoom_rooms.weekly_system_restart").asBoolean());
        checkSwitch(failures, "aggregator", aggregator.getControllableProperties(), stats, "AccountAlertSettings#ZoomRoomOffline",
                accountSettings.get("alert.notification.zoom_room_offline").asBoolean());
        checkSwitch(failures, "aggregator", aggregator.getControllableProperties(), stats, "AccountAlertSettings#BatteryLowAndNotCharging",
                accountSettings.get("alert.notification.battery_low_and_not_charging").asBoolean());
        for (AdvancedControllableProperty control : aggregator.getControllableProperties()) {
            if (!stats.containsKey(control.getName())) {
                failures.add("aggregator control " + control.getName() + " has no statistics entry");
            }
        }
        if (aggregator.getControllableProperties().stream().noneMatch(c -> c.getName().startsWith("AccountAlertSettings#"))
                || aggregator.getControllableProperties().stream().noneMatch(c -> c.getName().startsWith("AccountMeetingSettings#"))) {
            failures.add("account settings controls missing: " + aggregator.getControllableProperties().size());
        }
        int perDay = state().path("rateLimit").path("limits").path("Pro").path("perDay").asInt();
        int remaining = Integer.parseInt(stats.get("DashboardMetricsDailyRateLimitRemaining"));
        int dayCount = state().path("rateLimit").path("dayCount").asInt();
        if (remaining > perDay || remaining < perDay - dayCount) {
            failures.add("DashboardMetricsDailyRateLimitRemaining " + remaining + " outside [" + (perDay - dayCount) + ", " + perDay + "]");
        }
        check(failures, "MonitoredDevicesTotal", String.valueOf(devices.size()), aggregator.getDynamicStatistics().get("MonitoredDevicesTotal"));
        for (String key : stats.keySet()) {
            if (key.startsWith("Errors#")) {
                failures.add("unexpected error statistic " + key + " = " + stats.get(key));
            }
        }
        for (Map.Entry<String, String> entry : stats.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                failures.add("aggregator statistic " + entry.getKey() + " is null or empty");
            }
        }
        Assertions.assertTrue(failures.isEmpty(), failures.size() + " mismatches:\n" + String.join("\n", failures.subList(0, Math.min(60, failures.size()))));
    }

    static boolean inCall(AggregatedDevice room) {
        if (room.getMonitoredStatistics() == null) {
            return false;
        }
        for (Statistics statistics : room.getMonitoredStatistics()) {
            if (statistics instanceof EndpointStatistics) {
                return ((EndpointStatistics) statistics).isInCall();
            }
        }
        return false;
    }

    /** A retrieval time stamp: ISO 8601 UTC, and within 10 minutes of now. */
    static void checkRecentTimestamp(List<String> failures, String what, String value) {
        try {
            long ageSeconds = Math.abs(java.time.Duration.between(java.time.Instant.parse(value), java.time.Instant.now()).getSeconds());
            if (ageSeconds > 600) {
                failures.add(what + " " + value + " is " + ageSeconds / 60 + " min away from now (" + java.time.Instant.now() + ")");
            }
        } catch (Exception e) {
            failures.add(what + " not an ISO 8601 UTC time: " + value);
        }
    }

    /** A switch: its control value and its statistics entry both equal the simulator's value. */
    static void checkSwitch(List<String> failures, String owner, List<AdvancedControllableProperty> controls, Map<String, String> statistics,
            String name, boolean expected) {
        AdvancedControllableProperty control = control(controls, name);
        if (control == null) {
            failures.add(owner + " " + name + ": no control");
            return;
        }
        check(failures, owner + " " + name + " control value", expected, switchOn(control.getValue()));
        check(failures, owner + " " + name + " statistics value", expected, switchOn(statistics.get(name)));
    }

    static void check(List<String> failures, String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures.add(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
