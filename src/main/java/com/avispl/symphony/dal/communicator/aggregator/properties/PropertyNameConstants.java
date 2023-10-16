/*
 * Copyright (c) 2021 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.properties;

/**
 * Properties used in code, to provide more consistency.
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public class PropertyNameConstants {
    /** @since 1.0.1 */
    public static final String METRICS_DATA_RETRIEVED_TIME = "Metrics#DataRetrievedTime";
    /** @since 1.0.1 */
    public static final String LIVE_MEETING_DATA_RETRIEVED_TIME = "LiveMeeting#DataRetrievedTime";

    public static final String ROOM_CONTROLS_MEETING_SETTINGS_GROUP = "RoomControlsMeetingSettings#";
    public static final String ROOM_CONTROLS_ALERT_SETTINGS_GROUP = "RoomControlsAlertSettings#";
    public static final String ACCOUNT_CONTROLS_MEETING_SETTINGS_GROUP = "AccountMeetingSettings#";
    public static final String ACCOUNT_CONTROLS_ALERT_SETTINGS_GROUP = "AccountMeetingSettings#";

    public static final String LEAVE_CURRENT_MEETING_CONTROL = "RoomControls#LeaveCurrentMeeting";
    public static final String LEAVE_CURRENT_MEETING_PROPERTY = "LeaveCurrentMeeting";
    public static final String END_CURRENT_MEETING_CONTROL = "RoomControls#EndCurrentMeeting";
    public static final String END_CURRENT_MEETING_PROPERTY = "EndCurrentMeeting";
    public static final String RESTART_ZOOM_ROOMS_CLIENT_CONTROL = "RoomControls#RestartZoomRoomsClient";
    public static final String START_ROOM_PMI_CONTROL = "RoomControls#StartRoomPersonalMeeting";
    public static final String START_ROOM_PMI_CONTROL_PROPERTY = "StartRoomPersonalMeeting";

    public static final String ROOM_USER_DETAILS_PMI = "RoomUserDetails#PMI";
    public static final String ROOM_USER_DETAILS_GROUP = "RoomUserDetails#";

    public static final String LOCATION_NAME = "Location#Name";
    public static final String LOCATION_ID = "Location#ID";

    public static final String LOCATION_ID_PROPERTY = "LocationId";
    public static final String DEVICE_TYPE_PROPERTY = "DeviceType";
    public static final String APP_VERSION_PROPERTY = "AppVersion";
    public static final String DEVICE_SYSTEM_PROPERTY = "DeviceSystem";
    public static final String DEVICE_STATUS_PROPERTY = "DeviceStatus";
    public static final String ROOM_DEVICES_TEMPLATE_PROPERTY = "RoomDevices%ss#%s";
    public static final String ROOM_DEVICES_GROUP = "RoomDevices#";
    public static final String ONLINE_APP_VERSIONS_PROPERTY = "OnlineAppVersions";
    public static final String OFFLINE_APP_VERSIONS_PROPERTY = "OfflineAppVersions";
    public static final String ONLINE_DEVICE_SYSTEMS_PROPERTY = "OnlineDeviceSystems";
    public static final String OFFLINE_DEVICE_SYSTEMS_PROPERTY = "OfflineDeviceSystems";
    public static final String ONLINE_DEVICES_TOTAL_PROPERTY = "OnlineDevicesTotal";
    public static final String OFFLINE_DEVICES_TOTAL_PROPERTY = "OfflineDevicesTotal";

    public static final String ROOM_CONTROLS_GROUP = "RoomControls#";

    public static final String LIVE_MEETING_GROUP = "LiveMeeting#";
    public static final String LIVE_MEETING_GROUP_WARNING = "LiveMeeting#Warning";

    public static final String ROOM_STATUS_GROUP = "RoomStatus#";
    public static final String METRICS_ROOM_STATUS = "Metrics#RoomStatus";
    public static final String METRICS_ISSUES = "Metrics#Issues";

    public static final String ROOMS_LIST_RETRIEVAL_ERROR_KEY = "ZoomRoomsListRetrievalError";
    public static final String ROOMS_METRICS_RETRIEVAL_ERROR_KEY = "ZoomRoomsMetricsRetrievalError";
    public static final String LOGIN_ERROR_KEY = "LoginError";

    /** @since 1.2.0 */
    public static final String DEVICE_SYSTEM_KEY = "System";
    /** @since 1.2.0 */
    public static final String DEVICE_APP_VERSION_KEY = "AppVersion";
    /** @since 1.2.0 */
    public static final String DEVICE_FIRMWARE_KEY = "Firmware";
    /** @since 1.2.0 */
    public static final String DEVICE_IP_ADDRESS_KEY = "IPAddress";
    /** @since 1.2.0 */
    public static final String DEVICE_DEVICE_TYPE_KEY = "DeviceType";
    /** @since 1.2.0 */
    public static final String DEVICES_PATH = "/devices";
    /** @since 1.2.0 */
    public static final String ID_PATH = "/id";
    /** @since 1.2.0 */
    public static final String ROOM_NAME_PATH = "/room_name";
    /** @since 1.2.0 */
    public static final String HOSTNAME_PATH = "/device_hostname";
    /** @since 1.2.0 */
    public static final String SERIAL_NUMBER_PATH = "/serial_number";
    /** @since 1.2.0 */
    public static final String TYPE_PATH = "/device_type";
    /** @since 1.2.0 */
    public static final String MODEL_PATH = "/device_model";
    /** @since 1.2.0 */
    public static final String STATUS_PATH = "/status";
    /** @since 1.2.0 */
    public static final String MAC_ADDRESS_PATH = "/device_mac_addresses";
    /** @since 1.2.0 */
    public static final String SYSTEM_PATH = "/device_system";
    /** @since 1.2.0 */
    public static final String APP_VERSION_PATH = "/app_version";
    /** @since 1.2.0 */
    public static final String FIRMWARE_PATH = "/device_firmware";
    /** @since 1.2.0 */
    public static final String IP_ADDRESS_PATH = "/ip_address";
    /** @since 1.2.0 */
    public static final String ROOM_DEVICE_NAME_PATTERN = "%s: %s";
}
