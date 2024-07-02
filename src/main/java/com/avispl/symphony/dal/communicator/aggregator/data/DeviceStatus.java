package com.avispl.symphony.dal.communicator.aggregator.data;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Device status that reflects both Rooms and Room devices status descriptions
 *
 * @since 1.2.2
 * */
public enum DeviceStatus {
    OFFLINE("Offline"),
    ONLINE("Online"),
    AVAILABLE("Available"),
    IN_MEETING("InMeeting"),
    UNDER_CONSTRUCTION("UnderConstruction");

    /**
     * String value of the device status
     * */
    private String value;

    private DeviceStatus(final String value) {
        this.value = value;
    }

    /**
     * Gets value of {@link #value}
     * */
    public String getValue() {
        return value;
    }

    /**
     * Retrieved DeviceStatus instance based on a string input
     * Supported values are Offline ┃ Available ┃ InMeeting ┃ UnderConstruction
     *
     * @param value string value to get DeviceStatus instance for
     * @return DeviceStatus instance
     * */
    public static DeviceStatus ofString(String value) {
        Optional<DeviceStatus> selectedAuthMode = Arrays.stream(DeviceStatus.values()).filter(roomStatus -> Objects.equals(value, roomStatus.value)).findFirst();
        return selectedAuthMode.orElse(DeviceStatus.OFFLINE);
    }

    /**
     * Check whether the device status matches online state
     *
     * @param value string value to check online status for
     * @return boolean value reflecting online status
     * */
    public static boolean isOnline(String value) {
        DeviceStatus status = ofString(value);
        return DeviceStatus.OFFLINE != status && DeviceStatus.UNDER_CONSTRUCTION != status;
    }

    /**
     * Check whether the device call status matches inCall state
     *
     * @param value string value to check inCall status for
     * @return boolean value reflecting inCall status
     * */
    public static boolean isInCall(String value) {
        DeviceStatus status = ofString(value);
        return DeviceStatus.IN_MEETING == status;
    }
}
