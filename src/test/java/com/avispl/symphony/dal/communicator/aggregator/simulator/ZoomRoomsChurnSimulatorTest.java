/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * Device churn and edited data through the fleet admin API: rooms, room devices, users and locations added, removed
 * and edited. Changes appear and disappear within one collection interval, no stale devices, and the device list is
 * never emptied wholesale.
 */
public class ZoomRoomsChurnSimulatorTest extends ZoomRoomsSimulatorTestBase {

    @Test
    public void addedRemovedAndEditedEntitiesAppearWithinOneInterval() throws Exception {
        generateFleet(3, 3);
        JsonNode rooms = state().path("rooms");
        final String roomA = rooms.get(0).path("room_id").asText();
        final String roomB = rooms.get(1).path("room_id").asText();
        final String roomC = rooms.get(2).path("room_id").asText();
        final List<String> roomBDevices = deviceIds(rooms.get(1));
        final String removedDevice = rooms.get(0).path("devices").get(0).path("id").asText();
        final String editedDevice = rooms.get(2).path("devices").get(0).path("id").asText();
        final String userC = rooms.get(2).path("id").asText();
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        waitForFullCollection(communicator);

        ObjectNode newRoom = MAPPER.createObjectNode();
        newRoom.put("name", "Churn Room Added");
        newRoom.put("devices", 2);
        JsonNode added = admin(HttpMethod.POST, "/fleet/rooms", newRoom, 201);
        final String addedRoom = added.path("room_id").asText();
        final List<String> addedRoomDevices = deviceIds(admin(HttpMethod.GET, "/fleet/rooms/" + addedRoom, null, 200));
        admin(HttpMethod.DELETE, "/fleet/rooms/" + roomB, null, 204);
        final String addedDevice = admin(HttpMethod.POST, "/fleet/rooms/" + roomA + "/devices", "{}", 201).path("id").asText();
        admin(HttpMethod.DELETE, "/fleet/rooms/" + roomA + "/devices/" + removedDevice, null, 204);
        admin(HttpMethod.PATCH, "/fleet/rooms/" + roomA, "{\"name\": \"Churn Room Renamed\"}", 200);
        admin(HttpMethod.PATCH, "/fleet/rooms/" + roomC + "/devices/" + editedDevice, "{\"device_firmware\": \"9.9.9\", \"status\": \"Offline\"}", 200);
        admin(HttpMethod.PATCH, "/fleet/users/" + userC, "{\"timezone\": \"Europe/Kyiv\", \"role_name\": \"Admin\"}", 200);
        admin(HttpMethod.PATCH, "/fleet/rooms/" + roomC, "{\"status\": \"InMeeting\"}", 200);
        final String liveTopic = room(roomC).path("liveMeeting").path("topic").asText();
        Assertions.assertFalse(liveTopic.isEmpty(), "the simulator started a live meeting in " + roomC);

        Map<String, Predicate<List<AggregatedDevice>>> expectations = new LinkedHashMap<>();
        expectations.put("added room " + addedRoom + " reported", l -> byId(l, addedRoom) != null);
        expectations.put("added room's devices reported", l -> addedRoomDevices.stream().allMatch(id -> byId(l, id) != null));
        expectations.put("removed room " + roomB + " gone", l -> byId(l, roomB) == null);
        expectations.put("removed room's devices gone (no stale devices)", l -> roomBDevices.stream().allMatch(id -> byId(l, id) == null));
        expectations.put("added room device " + addedDevice + " reported", l -> byId(l, addedDevice) != null);
        expectations.put("removed room device " + removedDevice + " gone", l -> byId(l, removedDevice) == null);
        expectations.put("renamed room's deviceName updated", l -> byId(l, roomA) != null && "Churn Room Renamed".equals(byId(l, roomA).getDeviceName()));
        expectations.put("renamed room's device names updated", l -> roomDevices(l).stream()
                .filter(d -> roomA.equals(d.getProperties().get("ZoomRoomId"))).allMatch(d -> d.getDeviceName().startsWith("Churn Room Renamed: ")));
        expectations.put("edited device firmware", l -> byId(l, editedDevice) != null && "9.9.9".equals(byId(l, editedDevice).getProperties().get("Firmware")));
        expectations.put("edited device offline", l -> byId(l, editedDevice) != null && Boolean.FALSE.equals(byId(l, editedDevice).getDeviceOnline()));
        expectations.put("edited user timezone", l -> byId(l, roomC) != null && "Europe/Kyiv".equals(byId(l, roomC).getProperties().get("RoomUserDetails#Timezone")));
        expectations.put("edited user role", l -> byId(l, roomC) != null && "Admin".equals(byId(l, roomC).getProperties().get("RoomUserDetails#RoleName")));
        expectations.put("room status InMeeting", l -> byId(l, roomC) != null && "InMeeting".equals(byId(l, roomC).getProperties().get("Metrics#RoomStatus")));
        expectations.put("live meeting reported", l -> byId(l, roomC) != null && liveTopic.equals(byId(l, roomC).getProperties().get("LiveMeeting#Topic")));
        expectations.put("room in call", l -> byId(l, roomC) != null && ZoomRoomsBaselineSimulatorTest.inCall(byId(l, roomC)));

        List<String> unmet = awaitAll(communicator, expectations, roomC, ONE_INTERVAL_WAIT_MS);
        Assertions.assertTrue(unmet.isEmpty(), "not reported within one collection interval (" + ONE_INTERVAL_WAIT_MS / 1000 + " s):\n" + String.join("\n", unmet));
    }

    /**
     * Location filter (zoomRoomLocations) with locations edited through the fleet admin API: a room moved into the
     * filtered location appears, the location renamed away from the filter makes it disappear. Then the initial
     * "Building A" location (a name with a space) as the filter.
     */
    @Test
    public void locationFilterFollowsLocationEdits() throws Exception {
        JsonNode state = state();
        String campus = null;
        String buildingA = null;
        for (JsonNode location : state.path("locations")) {
            if ("campus".equals(location.path("type").asText())) {
                campus = location.path("id").asText();
            }
            if ("Building A".equals(location.path("name").asText())) {
                buildingA = location.path("id").asText();
            }
        }
        ObjectNode annex = MAPPER.createObjectNode();
        annex.put("name", "Annex");
        annex.put("type", "building");
        annex.put("parent_location_id", campus);
        final String annexId = admin(HttpMethod.POST, "/fleet/locations", annex, 201).path("id").asText();
        final String moved = state.path("rooms").get(0).path("room_id").asText();

        ZoomRoomsAggregatorCommunicator annexCommunicator = newCommunicator(c -> c.setZoomRoomLocations("Annex"));
        pollFor(annexCommunicator, 8000, list -> Assertions.assertTrue(list == null || list.isEmpty(), "no rooms in the empty Annex: " + (list == null ? 0 : list.size())));

        admin(HttpMethod.PATCH, "/fleet/rooms/" + moved, "{\"location_id\": \"" + annexId + "\"}", 200);
        pollUntil(annexCommunicator, "room moved into Annex reported, alone", ONE_INTERVAL_WAIT_MS, list -> rooms(list).size() == 1 && byId(list, moved) != null);

        admin(HttpMethod.PATCH, "/fleet/locations/" + annexId, "{\"name\": \"Outbuilding\"}", 200);
        pollUntil(annexCommunicator, "rooms gone once the location no longer matches the filter", ONE_INTERVAL_WAIT_MS, list -> rooms(list).isEmpty());

        // The initial locations have spaces in their names
        final List<String> inBuildingA = new ArrayList<>();
        for (JsonNode room : state().path("rooms")) {
            if (buildingA.equals(room.path("location_id").asText())) {
                inBuildingA.add(room.path("room_id").asText());
            }
        }
        annexCommunicator.destroy();
        final java.time.Instant buildingSince = java.time.Instant.now();
        ZoomRoomsAggregatorCommunicator buildingCommunicator = newCommunicator(c -> c.setZoomRoomLocations("Building A"));
        List<AggregatedDevice> last = null;
        long deadline = System.currentTimeMillis() + ONE_INTERVAL_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            last = pollOnce(buildingCommunicator);
            if (last != null && rooms(last).size() == inBuildingA.size()) {
                break;
            }
            Thread.sleep(POLL_PERIOD_MS);
        }
        List<String> reported = new ArrayList<>();
        if (last != null) {
            for (AggregatedDevice room : rooms(last)) {
                reported.add(room.getDeviceId());
            }
        }
        Assertions.assertEquals(inBuildingA.size(), reported.size(), "zoomRoomLocations=\"Building A\": expected the rooms " + inBuildingA
                + " but got " + reported + " (filter as stored by the adapter: \"" + buildingCommunicator.getZoomRoomLocations()
                + "\"). Requests: " + requestsSince(buildingSince, t -> t.startsWith("GET /v2/rooms")).stream()
                .map(r -> r.path("target").asText() + " -> " + r.path("status").asText()).distinct().collect(java.util.stream.Collectors.toList()));
        Assertions.assertTrue(reported.containsAll(inBuildingA), reported.toString());
    }

    /**
     * Polls until every expectation holds or the time is up. Meanwhile, on every poll, the device list must not be
     * empty and the untouched room must stay reported (never emptied wholesale). Returns the unmet expectations.
     */
    private List<String> awaitAll(ZoomRoomsAggregatorCommunicator communicator, Map<String, Predicate<List<AggregatedDevice>>> expectations,
            String alwaysPresent, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> unmet = new ArrayList<>(expectations.keySet());
        while (System.currentTimeMillis() < deadline && !unmet.isEmpty()) {
            List<AggregatedDevice> devices = pollOnce(communicator);
            if (devices != null) {
                Assertions.assertFalse(devices.isEmpty(), "device list emptied");
                Assertions.assertNotNull(byId(devices, alwaysPresent), "untouched room " + alwaysPresent + " dropped from the list");
                unmet.clear();
                for (Map.Entry<String, Predicate<List<AggregatedDevice>>> entry : expectations.entrySet()) {
                    if (!entry.getValue().test(devices)) {
                        unmet.add(entry.getKey());
                    }
                }
            }
            Thread.sleep(POLL_PERIOD_MS);
        }
        return unmet;
    }

    private static List<String> deviceIds(JsonNode room) {
        List<String> result = new ArrayList<>();
        for (JsonNode device : room.path("devices")) {
            result.add(device.path("id").asText());
        }
        return result;
    }
}
