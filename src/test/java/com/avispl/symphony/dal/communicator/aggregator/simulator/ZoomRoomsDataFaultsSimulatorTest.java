/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * Transport faults on the data endpoints (presets noResponse, droppedConnection, malformed, malformedField): the
 * affected group fails or degrades within the adapter's timeouts, no garbage values, the other groups stay intact,
 * the collector stays alive and the next cycle after the fault is cleared recovers.
 */
public class ZoomRoomsDataFaultsSimulatorTest extends ZoomRoomsSimulatorTestBase {

    /**
     * noResponse on the metrics list (COUNT 1): the held refresh must fail within the adapter timeout; meanwhile the
     * cached data stays reported; afterwards the metrics refresh again and the collector keeps cycling.
     */
    @Test
    public void unansweredMetricsRequestFailsWithinTimeoutAndCollectionRecovers() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final int expected = waitForFullCollection(communicator).size();
        final Map<String, String> retrievedTimes = retrievedTimes(pollOnce(communicator));
        final String ruleId = applyPreset("noResponse", "GET /v2/metrics/zoomrooms", "COUNT", 1);

        waitUntil(communicator, "the held metrics request", ONE_INTERVAL_WAIT_MS, () -> rule(ruleId).path("fired").asInt() == 1);
        final List<String> polls = new ArrayList<>();
        pollFor(communicator, 15_000, list -> polls.add(list == null ? "threw" : String.valueOf(list.size())));
        for (String poll : polls) {
            Assertions.assertEquals(String.valueOf(expected), poll, "cached devices reported while the metrics request is held: " + polls);
        }

        // Recovery: fresh metrics and a new rooms-list cycle after the held request ended (the rule fired once)
        List<AggregatedDevice> recovered = pollUntil(communicator, "metrics refreshed after the held request", 120_000 + ONE_INTERVAL_WAIT_MS, list -> {
            for (AggregatedDevice room : rooms(list)) {
                String time = room.getProperties().get("Metrics#DataRetrievedTime");
                if (time == null || time.equals(retrievedTimes.get(room.getDeviceId()))) {
                    return false;
                }
            }
            return list.size() == expected;
        });
        Assertions.assertEquals(expected, recovered.size());
        JsonNode held = null;
        for (JsonNode request : requests()) {
            if (request.path("fault").asText().contains(ruleId.substring(0, 8))) {
                held = request;
            }
        }
        Assertions.assertNotNull(held, "held request in the log");
        System.out.println("[simulatorTest] held metrics request: " + held.path("status").asText() + " after " + held.path("durationMs").asLong() + " ms");
        Assertions.assertTrue(held.path("durationMs").asLong() < communicator.getTimeout() + 5000, "the held metrics request must fail within the adapter timeout ("
                + communicator.getTimeout() + " ms); the simulator held it " + held.path("durationMs").asLong() + " ms (its no-response cap) and the collector waited");
    }

    /** droppedConnection on user details: the user group fails, other groups intact, recovered after clearing. */
    @Test
    public void droppedUserDetailsFailOnlyTheUserGroup() throws Exception {
        assertGroupFailsAndRecovers("droppedConnection", "GET /v2/users/*", "RoomUserDetails#");
    }

    /** malformed (truncated) room settings: the settings group and its controls fail, others intact, recovered. */
    @Test
    public void truncatedRoomSettingsFailOnlyTheSettingsGroup() throws Exception {
        assertGroupFailsAndRecovers("malformed", "GET /v2/rooms/*/settings", "RoomControls");
    }

    /**
     * malformedField on the room devices list: room devices are the affected group (they may drop out), the rooms and
     * their other groups stay, and the room devices return after clearing.
     */
    @Test
    public void corruptedRoomDevicesListAffectsOnlyRoomDevices() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final List<AggregatedDevice> established = waitForFullCollection(communicator);
        final int roomCount = rooms(established).size();
        final String ruleId = applyPreset("malformedField", "GET /v2/rooms/*/devices", null, null);
        waitUntil(communicator, "corrupted room devices answers", ONE_INTERVAL_WAIT_MS, () -> rule(ruleId).path("fired").asInt() >= 2 * roomCount);
        final List<String> observed = new ArrayList<>();
        pollFor(communicator, COLLECTION_INTERVAL_MS + 5000, list -> {
            Assertions.assertNotNull(list, "retrieveMultipleStatistics threw");
            Assertions.assertEquals(roomCount, rooms(list).size(), "rooms intact");
            for (AggregatedDevice room : rooms(list)) {
                Assertions.assertNotNull(room.getProperties().get("Metrics#Email"), "metrics intact");
                Assertions.assertNotNull(room.getProperties().get("RoomUserDetails#Email"), "user details intact");
            }
            for (AggregatedDevice device : roomDevices(list)) {
                Assertions.assertFalse(String.valueOf(device.getSerialNumber()).contains("#@!"), "garbage value " + device.getSerialNumber());
            }
            observed.add(String.valueOf(roomDevices(list).size()));
        });
        System.out.println("[simulatorTest] room devices reported while the devices list is corrupted: " + observed + " (of "
                + roomDevices(established).size() + ")");
        clearFaults();
        pollUntil(communicator, "room devices back after clearing", ONE_INTERVAL_WAIT_MS, list -> list.size() == established.size());
    }

    /**
     * droppedConnection on the rooms list, once: the rooms list request of one cycle fails. The device list must not
     * be emptied wholesale (the previous rooms and devices stay until a successful list says otherwise).
     */
    @Test
    public void droppedRoomsListDoesNotEmptyTheDeviceList() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final int expected = waitForFullCollection(communicator).size();
        final String ruleId = applyPreset("droppedConnection", "GET /v2/rooms", "COUNT", 1);
        final List<String> polls = new ArrayList<>();
        waitUntil(communicator, "the dropped rooms list request", ONE_INTERVAL_WAIT_MS, () -> {
            List<AggregatedDevice> list = pollOnce(communicator);
            polls.add(list == null ? "threw" : String.valueOf(list.size()));
            return rule(ruleId).path("fired").asInt() == 1;
        });
        pollFor(communicator, COLLECTION_INTERVAL_MS + 10_000, list -> polls.add(list == null ? "threw" : String.valueOf(list.size())));
        System.out.println("[simulatorTest] device list per poll around the dropped rooms list: " + polls);
        for (String poll : polls) {
            Assertions.assertEquals(String.valueOf(expected), poll, "device list per poll (never emptied wholesale): " + polls);
        }
    }

    /**
     * Applies an ALWAYS rule to one group's endpoint: once it fired, that group's properties (and controls) are gone or
     * unchanged but never garbage, every other group is intact on every room, and after clearing the group returns
     * within one collection interval.
     */
    private void assertGroupFailsAndRecovers(String preset, String target, String groupPrefix) throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final List<AggregatedDevice> established = waitForFullCollection(communicator);
        final int expected = established.size();
        final int roomCount = rooms(established).size();
        final String ruleId = applyPreset(preset, target, null, null);
        final Instant since = Instant.now();
        waitUntil(communicator, preset + " on " + target, ONE_INTERVAL_WAIT_MS, () -> rule(ruleId).path("fired").asInt() >= roomCount);
        Thread.sleep(3000);

        List<AggregatedDevice> during = communicator.retrieveMultipleStatistics();
        Assertions.assertEquals(expected, during.size(), "device list intact");
        List<String> groupState = new ArrayList<>();
        for (AggregatedDevice room : rooms(during)) {
            Map<String, String> p = room.getProperties();
            Assertions.assertNotNull(p.get("Metrics#Email"), "metrics intact on " + room.getDeviceId());
            Assertions.assertTrue(p.keySet().stream().anyMatch(k -> k.startsWith("RoomDevices")), "room devices group intact on " + room.getDeviceId());
            if (!"RoomUserDetails#".equals(groupPrefix)) {
                Assertions.assertNotNull(p.get("RoomUserDetails#Email"), "user details intact on " + room.getDeviceId());
            }
            if (!"RoomControls".equals(groupPrefix)) {
                Assertions.assertTrue(p.keySet().stream().anyMatch(k -> k.startsWith("RoomControls")), "settings intact on " + room.getDeviceId());
            }
            for (Map.Entry<String, String> entry : p.entrySet()) {
                Assertions.assertFalse(entry.getValue() != null && entry.getValue().contains("#@!"), "garbage value " + entry);
            }
            long groupProperties = p.keySet().stream().filter(k -> k.startsWith(groupPrefix)).count();
            long groupControls = room.getControllableProperties() == null ? 0
                    : room.getControllableProperties().stream().filter(c -> c.getName().startsWith(groupPrefix)).count();
            groupState.add(room.getDeviceId() + ": " + groupProperties + " properties, " + groupControls + " controls");
        }
        System.out.println("[simulatorTest] " + preset + " on " + target + ": " + groupPrefix + " during the fault " + groupState
                + "; requests " + requestsSince(since, t -> t.startsWith(target.substring(0, target.indexOf('*')))).size());

        clearFaults();
        pollUntil(communicator, groupPrefix + " back on every room after clearing", ONE_INTERVAL_WAIT_MS, list -> list.size() == expected
                && rooms(list).stream().allMatch(room -> room.getProperties().keySet().stream().anyMatch(k -> k.startsWith(groupPrefix))));
    }

    private static Map<String, String> retrievedTimes(List<AggregatedDevice> devices) {
        Map<String, String> result = new HashMap<>();
        for (AggregatedDevice room : rooms(devices)) {
            result.put(room.getDeviceId(), room.getProperties().get("Metrics#DataRetrievedTime"));
        }
        return result;
    }
}
