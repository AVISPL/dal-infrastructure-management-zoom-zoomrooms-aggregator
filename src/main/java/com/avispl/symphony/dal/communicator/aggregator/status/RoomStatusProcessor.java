/*
 * Copyright (c) 2021 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.status;

import com.avispl.symphony.dal.util.StringUtils;

import java.util.HashMap;
import java.util.Map;


/**
 * Process Zoom issues, based on a list of known issues strings
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public class RoomStatusProcessor {
    private static final Map<String, String> defaults = new HashMap<>();
    private static final Map<String, String> knownStatusList = new HashMap<>();

    static {
        defaults.put("RoomController", "CONNECTED");
        defaults.put("SelectedCamera", "CONNECTED");
        defaults.put("SelectedMicrophone", "CONNECTED");
        defaults.put("SelectedSpeaker", "CONNECTED");
        defaults.put("ZoomRoom", "ONLINE");
        defaults.put("CPU", "OK");
        defaults.put("Bandwidth", "OK");
        defaults.put("ZoomRoomsComputerBatteryLevel", "OK"); // ok, low or charging
        defaults.put("ZoomRoomsComputerBatteryStatus", "OK"); // ok, low or charging
        defaults.put("ZoomRoomsComputerConnection", "OK");
        defaults.put("ControllerBatteryLevel", "OK"); // ok, low or charging
        defaults.put("ControllerBatteryStatus", "OK"); // ok, low or charging
        defaults.put("ControllerConnection", "OK");
        defaults.put("SchedulingDisplayBatteryLevel", "OK"); // ok, low or charging
        defaults.put("SchedulingDisplayBatteryStatus", "OK"); // ok, low or charging
        defaults.put("SchedulingDisplayConnection", "OK");

        knownStatusList.put("room controller disconnected", "RoomController:DISCONNECTED");
        knownStatusList.put("room controller connected", "RoomController:CONNECTED");
        knownStatusList.put("selected camera has disconnected", "SelectedCamera:DISCONNECTED");
        knownStatusList.put("selected camera is reconnected", "SelectedCamera:CONNECTED");
        knownStatusList.put("selected microphone has disconnected", "SelectedMicrophone:DISCONNECTED");
        knownStatusList.put("selected microphone is reconnected", "SelectedMicrophone:CONNECTED");
        knownStatusList.put("selected speaker has disconnected", "SelectedSpeaker:DISCONNECTED");
        knownStatusList.put("selected speaker is reconnected", "SelectedSpeaker:CONNECTED");
        knownStatusList.put("zoom room is offline", "ZoomRoom:OFFLINE");
        knownStatusList.put("zoom room is online", "ZoomRoom:ONLINE");
        knownStatusList.put("high cpu usage is detected", "CPU:HIGH");
        knownStatusList.put("low bandwidth network is detected", "Bandwidth:LOW");
        knownStatusList.put("zoom rooms computer battery is low", "ZoomRoomsComputerBatteryLevel:LOW");
        knownStatusList.put("controller battery is low", "ControllerBatteryLevel:LOW");
        knownStatusList.put("scheduling display battery is low", "SchedulingDisplayBatteryLevel:LOW");
        knownStatusList.put("zoom rooms computer battery is normal", "ZoomRoomsComputerBatteryLevel:OK");
        knownStatusList.put("controller battery is normal", "ControllerBatteryLevel:OK");
        knownStatusList.put("scheduling display battery is normal", "SchedulingDisplayBatteryLevel:OK");
        knownStatusList.put("zoom rooms computer disconnected", "ZoomRoomsComputerConnection:DISCONNECTED");
        knownStatusList.put("controller disconnected", "ControllerConnection:DISCONNECTED");
        knownStatusList.put("scheduling display disconnected", "SchedulingDisplayConnection:DISCONNECTED");
        knownStatusList.put("zoom rooms computer is not charging", "ZoomRoomsComputerBatteryStatus:NOT_CHARGING");
        knownStatusList.put("controller is not charging", "ControllerBatteryStatus:NOT_CHARGING");
        knownStatusList.put("scheduling display is not charging", "SchedulingDisplayBatteryStatus:NOT_CHARGING");
    }

    /**
     * Get a list of issues from the Zoom API and transform it into a consistent map of status properties.
     *
     * @param issuesLine "issues" node from Zoom API. Essentially, just a comma-separated string of issues
     * @return {@code Map<String, String>} based on {@link #defaults} map
     */
    public static Map<String, String> processIssuesList(String issuesLine) {
        Map<String, String> issues = new HashMap<>(defaults);

        if(StringUtils.isNullOrEmpty(issuesLine)) {
            return issues;
        }

        String preparedLine = issuesLine.replace("[", "").replace("]", "").replace("\"", "");
        for(String value: preparedLine.split(",")) {
            String status = knownStatusList.get(value.trim().toLowerCase());
            if (!StringUtils.isNullOrEmpty(status)) {
                String[] statusDetails = status.split(":");
                issues.put(statusDetails[0], statusDetails[1]);
            }
        }
        String zoomRoomStatus = issues.get("ZoomRoom");
        if (zoomRoomStatus.equals("OFFLINE")) {
            issues.forEach((key, value) -> {
                if (!key.equals("ZoomRoom")) {
                    issues.put(key, "N/A");
                }
            });
        }
        return issues;
    }
}
