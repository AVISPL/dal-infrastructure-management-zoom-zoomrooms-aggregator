/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** How long the simulator holds a noResponse request before dropping it (simulator.no-response-cap-ms). */
    private static final long NO_RESPONSE_CAP_MS = 120_000;

    /**
     * noResponse on the metrics list until cleared (a lasting outage): once the first held request fails (when the
     * simulator drops it), both retrieveMultipleStatistics and getMultipleStatistics throw
     * ResourceNotReachableException to their caller; after the fault is cleared the metrics refresh again, the calls
     * stop throwing and the collector keeps cycling.
     * <p>
     * ALWAYS, not COUNT 1: with a single held request the metrics are refetched right after it fails (its cooldown
     * has long expired) and succeed, which clears the exception within one cycle - correct, but too brief to poll.
     */
    @Test
    public void unansweredMetricsRequestFailsWithinTimeoutAndCollectionRecovers() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final int expected = waitForFullCollection(communicator).size();
        final Map<String, String> retrievedTimes = retrievedTimes(pollOnce(communicator));
        final String ruleId = applyPreset("noResponse", "GET /v2/metrics/zoomrooms", null, null);

        waitUntil(communicator, "the held metrics request", ONE_INTERVAL_WAIT_MS, () -> rule(ruleId).path("fired").asInt() >= 1);
        final long heldSince = System.currentTimeMillis();
        final String unreachable = "ResourceNotReachableException/ResourceNotReachableException";
        // Upper bound: the request is held until the simulator drops it (its no-response cap, 120 s), since
        // symphony-dal-commons 6.5.0 does not apply the adapter timeout to requests; plus poll period and slack
        final long reportWithinMs = NO_RESPONSE_CAP_MS + POLL_PERIOD_MS + 5000;
        waitUntil(null, "both calls throwing ResourceNotReachableException", reportWithinMs, () -> unreachable.equals(callBoth(communicator)));
        System.out.println("[simulatorTest] both calls threw ResourceNotReachableException " + (System.currentTimeMillis() - heldSince)
                + " ms after the held request arrived");

        // Recovery: after clearing, a request already held may still be held for up to the cap, then fresh metrics
        // within one collection interval
        clearFaults();
        List<AggregatedDevice> recovered = pollUntil(communicator, "metrics refreshed after the fault was cleared", NO_RESPONSE_CAP_MS + ONE_INTERVAL_WAIT_MS, list -> {
            for (AggregatedDevice room : rooms(list)) {
                String time = room.getProperties().get("Metrics#DataRetrievedTime");
                if (time == null || time.equals(retrievedTimes.get(room.getDeviceId()))) {
                    return false;
                }
            }
            return list.size() == expected;
        });
        Assertions.assertEquals(expected, recovered.size());
        // The held request itself is not looked up in the simulator's request log: it is only logged when the
        // simulator's hold ends (120 s), long after the adapter gave up
        Assertions.assertEquals(expected + " devices/stats ok", callBoth(communicator), "calls stop throwing after recovery");
    }

    /** droppedConnection on user details: the user group fails, other groups intact, recovered after clearing. */
    @Test
    public void droppedUserDetailsFailOnlyTheUserGroup() throws Exception {
        assertGroupFailsAndRecovers("droppedConnection", "GET /v2/users/*", "RoomUserDetails#", "RoomUserDetails");
    }

    /** malformed (truncated) room settings: the settings group and its controls fail, others intact, recovered. */
    @Test
    public void truncatedRoomSettingsFailOnlyTheSettingsGroup() throws Exception {
        assertGroupFailsAndRecovers("malformed", "GET /v2/rooms/*/settings", "RoomControls", "RoomControlSettings");
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
     * droppedConnection on the rooms list, once: the API is unreachable for one rooms list request. Until the next
     * successful list, retrieveMultipleStatistics and getMultipleStatistics throw ResourceNotReachableException to
     * their caller; the device list is never emptied (a poll either throws or returns every device), and once the list
     * succeeds again both calls return normally with every device.
     */
    @Test
    public void droppedRoomsListDoesNotEmptyTheDeviceList() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final int expected = waitForFullCollection(communicator).size();
        final String ok = expected + " devices/stats ok";
        final String unreachable = "ResourceNotReachableException/ResourceNotReachableException";
        final String ruleId = applyPreset("droppedConnection", "GET /v2/rooms", "COUNT", 1);
        final List<String> polls = new ArrayList<>();
        waitUntil(null, "the dropped rooms list request", ONE_INTERVAL_WAIT_MS, () -> {
            polls.add(callBoth(communicator));
            return rule(ruleId).path("fired").asInt() == 1;
        });
        long deadline = System.currentTimeMillis() + COLLECTION_INTERVAL_MS + 10_000;
        while (System.currentTimeMillis() < deadline) {
            polls.add(callBoth(communicator));
            Thread.sleep(POLL_PERIOD_MS);
        }
        System.out.println("[simulatorTest] polls around the dropped rooms list (devices/statistics): " + polls);
        for (String poll : polls) {
            Assertions.assertTrue(poll.equals(ok) || poll.equals(unreachable), "each poll returns every device or throws ResourceNotReachableException from both calls: " + polls);
        }
        Assertions.assertTrue(polls.contains(unreachable), "the unreachable rooms list is thrown to the callers: " + polls);
        waitUntil(null, "calls stop throwing after a successful rooms list", ONE_INTERVAL_WAIT_MS, () -> ok.equals(callBoth(communicator)));
    }

    /**
     * Applies an ALWAYS rule to one group's endpoint: once it fired, that group's properties (and controls) are gone or
     * unchanged but never garbage, every other group is intact on every room, every room reports the group's failure
     * as its error (Errors#&lt;roomId&gt;), and after clearing the group returns and the errors are cleared within one
     * collection interval.
     */
    private void assertGroupFailsAndRecovers(String preset, String target, String groupPrefix, String reportedGroup) throws Exception {
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

        Map<String, String> errors = reportedErrors(communicator);
        System.out.println("[simulatorTest] errors reported during the fault: " + errors);
        for (AggregatedDevice room : rooms(during)) {
            String error = errors.get(room.getDeviceId());
            Assertions.assertNotNull(error, "room " + room.getDeviceId() + " reports the failed group: " + errors);
            Assertions.assertTrue(error.contains(reportedGroup), "error of room " + room.getDeviceId() + " names " + reportedGroup + ": " + error);
        }

        clearFaults();
        pollUntil(communicator, groupPrefix + " back on every room after clearing", ONE_INTERVAL_WAIT_MS, list -> list.size() == expected
                && rooms(list).stream().allMatch(room -> room.getProperties().keySet().stream().anyMatch(k -> k.startsWith(groupPrefix))));
        waitUntil(communicator, "room errors cleared after the group recovered", ONE_INTERVAL_WAIT_MS,
                () -> rooms(during).stream().noneMatch(room -> reportedErrors(communicator).containsKey(room.getDeviceId())));
    }

    private static Map<String, String> retrievedTimes(List<AggregatedDevice> devices) {
        Map<String, String> result = new HashMap<>();
        for (AggregatedDevice room : rooms(devices)) {
            result.put(room.getDeviceId(), room.getProperties().get("Metrics#DataRetrievedTime"));
        }
        return result;
    }
}
