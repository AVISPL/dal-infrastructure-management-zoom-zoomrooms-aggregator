/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * Control round-trips for every room settings switch (meeting and alert groups) and every account settings switch,
 * both ways: controlProperty, then the simulator state changed (exactly the one setting), the adapter shows the new
 * value at once, the next poll reads the same value back from the simulator; then the original value is restored and
 * checked the same way.
 * <p>
 * Effect (simulator README "Controls"): a switch PATCHes one boolean of {@code rooms[i].settings.<type>.<group>} or
 * {@code accountSettings.<type>.<group>}, visible on the next GET, answered 204. Switches only: the room's
 * BatteryPercentage control is Numeric and the simulator accepts booleans only (README A23).
 */
public class ZoomRoomsControlsSimulatorTest extends ZoomRoomsSimulatorTestBase {

    private static final String AGGREGATOR_DEVICE_ID = "zoom-rooms-aggregator";

    @Test
    public void everyRoomSettingsSwitchRoundTripsBothWays() throws Exception {
        generateFleet(2, 3);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        List<AggregatedDevice> devices = waitForFullCollection(communicator);
        String roomId = state().path("rooms").get(0).path("room_id").asText();
        AggregatedDevice room = byId(devices, roomId);
        Assertions.assertNotNull(room, "room " + roomId);

        Map<String, Boolean> originals = new LinkedHashMap<>();
        for (AdvancedControllableProperty control : room.getControllableProperties()) {
            if (control.getType() instanceof AdvancedControllableProperty.Switch
                    && (control.getName().startsWith("RoomControlsMeetingSettings#") || control.getName().startsWith("RoomControlsAlertSettings#"))) {
                originals.put(control.getName(), switchOn(control.getValue()));
            }
        }
        Assertions.assertTrue(originals.keySet().stream().anyMatch(name -> name.startsWith("RoomControlsMeetingSettings#")), "meeting switches");
        Assertions.assertTrue(originals.keySet().stream().anyMatch(name -> name.startsWith("RoomControlsAlertSettings#")), "alert switches");
        Map<String, JsonNode> initialSettings = flatten(room(roomId).path("settings"));

        List<String> failures = new ArrayList<>();
        // There: every switch to the opposite value
        Map<String, Boolean> targets = new LinkedHashMap<>();
        originals.forEach((name, original) -> targets.put(name, !original));
        roundTrip(communicator, roomId, targets, failures, "toggle");
        // Back: every switch to its original value
        roundTrip(communicator, roomId, originals, failures, "restore");

        Map<String, String> left = diff(initialSettings, flatten(room(roomId).path("settings")));
        if (!left.isEmpty()) {
            failures.add("room settings not restored in the simulator: " + left);
        }
        Assertions.assertTrue(failures.isEmpty(), failures.size() + " of " + originals.size() + " room switches x 2 directions failed:\n"
                + String.join("\n", failures));
    }

    /**
     * Sends every target value, checking the simulator and the adapter's cache after each one, then waits for the next
     * room settings poll and checks every value was read back.
     */
    private void roundTrip(ZoomRoomsAggregatorCommunicator communicator, String roomId, Map<String, Boolean> targets, List<String> failures,
            String phase) throws Exception {
        List<String> sent = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : targets.entrySet()) {
            String name = entry.getKey();
            boolean target = entry.getValue();
            Map<String, JsonNode> before = flatten(room(roomId).path("settings"));
            try {
                communicator.controlProperty(controllable(roomId, name, target));
            } catch (Exception e) {
                failures.add(phase + " " + name + " -> " + target + ": controlProperty threw " + e);
                continue;
            }
            sent.add(name);
            Map<String, String> changed = diff(before, flatten(room(roomId).path("settings")));
            if (changed.size() != 1 || !changed.values().iterator().next().endsWith("-> " + target)) {
                failures.add(phase + " " + name + " -> " + target + ": simulator settings changed " + changed + " (expected exactly one setting to " + target + ")");
            }
            AggregatedDevice cached = byId(pollOnce(communicator), roomId);
            AdvancedControllableProperty control = control(cached.getControllableProperties(), name);
            if (control == null || switchOn(control.getValue()) != target || switchOn(cached.getProperties().get(name)) != target) {
                failures.add(phase + " " + name + " -> " + target + ": adapter shows control " + (control == null ? "none" : control.getValue())
                        + ", statistics " + cached.getProperties().get(name) + " right after the control");
            }
        }
        if (sent.isEmpty()) {
            return;
        }
        // Next poll: the room's settings are requested again after the controls, then read back
        final Instant afterControls = Instant.now();
        waitUntil(communicator, "next room settings poll of " + roomId, ONE_INTERVAL_WAIT_MS, () -> !requestsSince(afterControls,
                target -> target.equals("GET /v2/rooms/" + roomId + "/settings?setting_type=alert")).isEmpty());
        Thread.sleep(3000);
        AggregatedDevice polled = byId(pollOnce(communicator), roomId);
        for (String name : sent) {
            boolean target = targets.get(name);
            AdvancedControllableProperty control = control(polled.getControllableProperties(), name);
            if (control == null || switchOn(control.getValue()) != target) {
                failures.add(phase + " " + name + " -> " + target + ": next poll reads control " + (control == null ? "none" : control.getValue()));
            }
            if (switchOn(polled.getProperties().get(name)) != target) {
                failures.add(phase + " " + name + " -> " + target + ": next poll reads statistics " + polled.getProperties().get(name));
            }
        }
    }

    @Test
    public void everyAccountSettingsSwitchRoundTripsBothWays() throws Exception {
        generateFleet(1, 1);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        ExtendedStatistics statistics = aggregatorStatistics(communicator);
        Map<String, Boolean> originals = new LinkedHashMap<>();
        for (AdvancedControllableProperty control : statistics.getControllableProperties()) {
            if (control.getType() instanceof AdvancedControllableProperty.Switch
                    && (control.getName().startsWith("AccountMeetingSettings#") || control.getName().startsWith("AccountAlertSettings#"))) {
                originals.put(control.getName(), switchOn(control.getValue()));
            }
        }
        Assertions.assertTrue(originals.keySet().stream().anyMatch(name -> name.startsWith("AccountMeetingSettings#")), "meeting switches");
        Assertions.assertTrue(originals.keySet().stream().anyMatch(name -> name.startsWith("AccountAlertSettings#")), "alert switches");
        Map<String, JsonNode> initialSettings = flatten(state().path("accountSettings"));

        List<String> failures = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            String phase = pass == 0 ? "toggle" : "restore";
            for (Map.Entry<String, Boolean> entry : originals.entrySet()) {
                String name = entry.getKey();
                boolean target = pass == 0 ? !entry.getValue() : entry.getValue();
                Map<String, JsonNode> before = flatten(state().path("accountSettings"));
                try {
                    communicator.controlProperty(controllable(AGGREGATOR_DEVICE_ID, name, target));
                } catch (Exception e) {
                    failures.add(phase + " " + name + " -> " + target + ": controlProperty threw " + e);
                    continue;
                }
                Map<String, String> changed = diff(before, flatten(state().path("accountSettings")));
                if (changed.size() != 1 || !changed.values().iterator().next().endsWith("-> " + target)) {
                    failures.add(phase + " " + name + " -> " + target + ": simulator account settings changed " + changed
                            + " (expected exactly one setting to " + target + ")");
                }
                // The aggregator reads the account settings on every getMultipleStatistics: at once, and on the next poll
                for (String poll : new String[] {"at once", "next poll"}) {
                    ExtendedStatistics after = aggregatorStatistics(communicator);
                    AdvancedControllableProperty control = control(after.getControllableProperties(), name);
                    if (control == null || switchOn(control.getValue()) != target) {
                        failures.add(phase + " " + name + " -> " + target + ": " + poll + " the adapter shows control " + (control == null ? "none" : control.getValue()));
                    }
                    if (switchOn(after.getStatistics().get(name)) != target) {
                        failures.add(phase + " " + name + " -> " + target + ": " + poll + " the adapter shows statistics " + after.getStatistics().get(name));
                    }
                }
            }
        }
        Map<String, String> left = diff(initialSettings, flatten(state().path("accountSettings")));
        if (!left.isEmpty()) {
            failures.add("account settings not restored in the simulator: " + left);
        }
        Assertions.assertTrue(failures.isEmpty(), failures.size() + " failures over " + originals.size() + " account switches x 2 directions:\n"
                + String.join("\n", failures));
    }

    private static ControllableProperty controllable(String deviceId, String name, boolean value) {
        ControllableProperty property = new ControllableProperty();
        property.setDeviceId(deviceId);
        property.setProperty(name);
        property.setValue(value ? "1" : "0");
        return property;
    }
}
