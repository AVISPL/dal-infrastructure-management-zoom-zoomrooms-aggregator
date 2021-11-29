/*
 * Copyright (c) 2021 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.avispl.symphony.dal.communicator.aggregator.settings.Setting;
import com.avispl.symphony.dal.communicator.aggregator.settings.ZoomRoomsSetting;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Communicator retrieves information about all the ZoomRooms on a specific account.
 * Provides all the necessary monitoring information, such as peripherals statuses, room statuses, network information etc.
 * Provides controls needed for account/rooms settings manipulation.
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public class ZoomRoomsAggregatorCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {

    private static final String BASE_ZOOM_URL = "v2";
    private static final String ZOOM_ROOMS_URL = "rooms?page_size=5000";
    private static final String ZOOM_DEVICES_URL = "rooms/%s/devices?page_size=5000"; // Requires room Id
    private static final String ZOOM_ROOM_PROFILE_URL = "rooms/%s"; // Requires room Id
    private static final String ZOOM_ROOM_SETTINGS_URL = "/rooms/%s/settings"; // Requires room Id
    private static final String ZOOM_ROOM_ACCOUNT_SETTINGS_URL = "rooms/account_settings";
    private static final String ZOOM_ROOM_METRICS = "metrics/zoomrooms?page_size=5000";
    private static final String ZOOM_UPDATE_APP_VERSION = "/rooms/%s/devices/%s/app_version";

    private static final String ZOOM_ROOM_CLIENT_RPC = "/rooms/%s/zrclient";
    private static final String ZOOM_ROOM_CLIENT_MEETINGS = "/rooms/%s/meetings";

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    private List<AggregatedDevice> aggregatedDevices;
    private Properties adapterProperties;
    private String devicesProperties;
    private String aggregatorProperties;
    private String authorizationToken;

    /**
     * Retrieves {@code {@link #devicesProperties}}
     *
     * @return value of {@link #devicesProperties}
     */
    public String getDevicesProperties() {
        return devicesProperties;
    }

    /**
     * Sets {@code devicesProperties}
     *
     * @param devicesProperties the {@code java.lang.String} field
     */
    public void setDevicesProperties(String devicesProperties) {
        this.devicesProperties = devicesProperties;
    }

    /**
     * Retrieves {@code {@link #aggregatorProperties}}
     *
     * @return value of {@link #aggregatorProperties}
     */
    public String getAggregatorProperties() {
        return aggregatorProperties;
    }

    /**
     * Sets {@code aggregatorProperties}
     *
     * @param aggregatorProperties the {@code java.lang.String} field
     */
    public void setAggregatorProperties(String aggregatorProperties) {
        this.aggregatorProperties = aggregatorProperties;
    }

    /**
     * Build an instance of ZoomRoomsAggregatorCommunicator
     * Setup aggregated devices processor, initialize adapter properties
     *
     * @throws IOException if unable to locate mapping ymp file or properties file
     * */
    public ZoomRoomsAggregatorCommunicator() throws IOException {
        aggregatedDevices = new ArrayList<>();
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
    }

    /** {@inheritDoc} */
    @Override
    protected void authenticate() throws Exception {
    }

    /** {@inheritDoc} */
    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String roomId = controllableProperty.getDeviceId();
        String property = controllableProperty.getProperty();
        Object value = controllableProperty.getValue();

        if (!StringUtils.isNullOrEmpty(roomId) && !StringUtils.isNullOrEmpty(property)) {
            if (property.startsWith("RoomControlsMeetingSettings#") || property.startsWith("RoomControlsAlertSettings#")) {
                Setting setting = Setting.fromString(ZoomRoomsSetting.valueOf(property.split("#")[1]).toString());
                if (setting == null) {
                    // TODO throw an error
                    return;
                }
                String settingValue = "0".equals(String.valueOf(value)) ? "false" : "true";
                updateRoomSetting(roomId, setting.getSettingName(), settingValue, setting.getSettingType(), setting.getParentNode());
                return;
            } else if (property.startsWith("AccountMeetingSettings#") || property.startsWith("AccountAlertSettings#")) {
                Setting setting = Setting.fromString(ZoomRoomsSetting.valueOf(property.split("#")[1]).toString());
                if (setting == null) {
                    // TODO throw an error
                    return;
                }
                String settingValue = "0".equals(String.valueOf(value)) ? "false" : "true";
                updateAccountSettings(setting.getSettingName(), settingValue, setting.getSettingType(), setting.getParentNode());
                return;
            } else {
                String id = retrieveIdByRoomId(roomId);
                switch (property) {
                    case "RoomControls#LeaveCurrentMeeting":
                        leaveCurrentMeeting(id);
                        break;
                    case "RoomControls#EndCurrentMeeting":
                        endCurrentMeeting(id);
                        break;
                    case "RoomControls#RestartZoomRoomsClient":
                        restartZoomRoomClient(id);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : controllablePropertyList) {
            controlProperty(controllableProperty);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        Map<String, String> statistics = new HashMap<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        List<AdvancedControllableProperty> accountSettingsControls = new ArrayList<>();
        aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings("meeting"), "AccountMeetingSettings");
        aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings("alert"), "AccountAlertSettings");

//        // if the property isn't there - we should not display this control and its label
        accountSettingsControls.removeIf(advancedControllableProperty -> {
            String value = String.valueOf(advancedControllableProperty.getValue());
            if(StringUtils.isNullOrEmpty(value)) {
                statistics.remove(advancedControllableProperty.getName());
                return true;
            }
            return false;
        });

        statistics.put("AdapterVersion", adapterProperties.getProperty("mock.aggregator.version"));
        statistics.put("AdapterBuildDate", adapterProperties.getProperty("mock.aggregator.build.date"));

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(accountSettingsControls);
        return Collections.singletonList(extendedStatistics);
    }

    /** {@inheritDoc} */
    @Override
    protected void internalInit() throws Exception {
        authorizationToken = getPassword();
        setBaseUri(BASE_ZOOM_URL);
        super.internalInit();
    }

    /** {@inheritDoc} */
    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();
        restTemplate.getMessageConverters().add(new Jaxb2RootElementHttpMessageConverter());
        return restTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * Zoom api endpoint does not have ICMP available, so this workaround is needed to provide
     * ping latency information to Symphony
     */
    @Override
    public int ping() throws Exception {
        if (isInitialized()) {
            long pingResultTotal = 0L;

            for (int i = 0; i < this.getPingAttempts(); i++) {
                long startTime = System.currentTimeMillis();

                try (Socket puSocketConnection = new Socket(this.getHost(), this.getPort())) {
                    puSocketConnection.setSoTimeout(this.getPingTimeout());

                    if (puSocketConnection.isConnected()) {
                        long pingResult = System.currentTimeMillis() - startTime;
                        pingResultTotal += pingResult;
                        if (this.logger.isTraceEnabled()) {
                            this.logger.trace(String.format("PING OK: Attempt #%s to connect to %s on port %s succeeded in %s ms", i + 1, this.getHost(), this.getPort(), pingResult));
                        }
                    } else {
                        if (this.logger.isDebugEnabled()) {
                            this.logger.debug(String.format("PING DISCONNECTED: Connection to %s did not succeed within the timeout period of %sms", this.getHost(), this.getPingTimeout()));
                        }
                        return this.getPingTimeout();
                    }
                } catch (SocketTimeoutException tex) {
                    if (this.logger.isDebugEnabled()) {
                        this.logger.debug(String.format("PING TIMEOUT: Connection to %s did not succeed within the timeout period of %sms", this.getHost(), this.getPingTimeout()));
                    }
                    return this.getPingTimeout();
                }
            }
            return Math.max(1, Math.toIntExact(pingResultTotal / this.getPingAttempts()));
        } else {
            throw new IllegalStateException("Cannot use device class without calling init() first");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", "bearer " + authorizationToken);
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    /** {@inheritDoc} */
    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
        List<AggregatedDevice> devices = aggregatedDeviceProcessor.extractDevices(retrieveZoomRooms());
        retrieveRoomDevicesProperties(devices);
        aggregatedDevices = devices;
        return devices;
    }

    /** {@inheritDoc} */
    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
                .collect(Collectors.toList());
    }

    /**
     * Build rpc request for rpc operations (client reboot, leave or end current meeting)
     *
     * @param method to use for rpc request
     * @return {@code Map<String, String>} request map
     */
    private Map<String, String> buildRpcRequest(String method) {
        Map<String, String> command = new HashMap<>();
        command.put("jsonrpc", "2.0");
        command.put("method", method);
        return command;
    }

    /**
     * Instantiate Text controllable property
     *
     * @param name         name of the property
     * @param label        default button label
     * @param labelPressed button label when is pressed
     * @param gracePeriod  period to pause monitoring statistics for
     * @return instance of AdvancedControllableProperty with AdvancedControllableProperty.Button as type
     */
    private AdvancedControllableProperty createButton(String name, String label, String labelPressed, long gracePeriod) {
        AdvancedControllableProperty.Button button = new AdvancedControllableProperty.Button();
        button.setLabel(label);
        button.setLabelPressed(labelPressed);
        button.setGracePeriod(gracePeriod);

        return new AdvancedControllableProperty(name, new Date(), button, "");
    }

    /**
     * Retrieve list of ZoomRooms available
     *
     * @return response JsonNode
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveZoomRooms() throws Exception {
        return doGet(ZOOM_ROOMS_URL, JsonNode.class);
    }

    /**
     * Populate ZoomRooms with properties: metrics, devices, controls etc.
     *
     * @param rooms list of ZoomRooms containing basic information, to add data to
     * @throws Exception if any error occurs
     */
    private void retrieveRoomDevicesProperties(List<AggregatedDevice> rooms) throws Exception {
        JsonNode roomMetrics = retrieveZoomRoomMetrics();
        Map<String, Map<String, String>> roomMetricsProperties = new HashMap<>();
        if (roomMetrics != null && roomMetrics.isArray()) {
            for (JsonNode roomMetric: roomMetrics) {
                HashMap<String, String> roomProperties = new HashMap<>();
                aggregatedDeviceProcessor.applyProperties(roomProperties, roomMetric, "ZoomRoomMetrics");
                roomMetricsProperties.put(roomMetric.get("id").asText(), roomProperties);
            }
        }

        Long currentTime = new Date().getTime();
        for (AggregatedDevice room : rooms) {
            String roomId = room.getDeviceId();
            room.getProperties().putAll(roomMetricsProperties.get(roomId));

            Map<String, String> settingsProperties = new HashMap<>();
            List<AdvancedControllableProperty> settingsControls = new ArrayList<>();

            JsonNode meetingSettings = retrieveRoomSettings(roomId, "meeting");
            aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, meetingSettings, "RoomMeetingSettings");
            JsonNode alertSettings = retrieveRoomSettings(roomId, "alert");
            aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, alertSettings, "RoomAlertSettings");

//            // if the property isn't there - we should not display this control and its label
            settingsControls.removeIf(advancedControllableProperty -> {
                String value = String.valueOf(advancedControllableProperty.getValue());
                if(StringUtils.isNullOrEmpty(value)) {
                    settingsProperties.remove(advancedControllableProperty.getName());
                    return true;
                }
                return false;
            });

            room.getProperties().putAll(settingsProperties);
            room.getControllableProperties().addAll(settingsControls);
            JsonNode devices = retrieveRoomDevices(roomId);
            if (devices != null && devices.isArray()) {
                for (JsonNode deviceNode : devices) {
                    Map<String, String> properties = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(properties, deviceNode, "RoomDevice");
                    for (Map.Entry<String, String> entry : properties.entrySet()) {
                        room.getProperties().put(String.format("RoomDevice_%s_%s", properties.get("Info#ID"), entry.getKey()), entry.getValue());
                    }
                }
            }

            if (room.getProperties().get("Metrics#RoomStatus").equals("InMeeting")) {
                room.getProperties().put("RoomControls#EndCurrentMeeting", "");
                room.getControllableProperties().add(createButton("RoomControls#EndCurrentMeeting", "End", "Ending...", 0L));

                room.getProperties().put("RoomControls#LeaveCurrentMeeting", "");
                room.getControllableProperties().add(createButton("RoomControls#LeaveCurrentMeeting", "Leave", "Leaving...", 0L));
            }

            if (!room.getProperties().get("Metrics#RoomStatus").equals("Offline")) {
                room.getProperties().put("RoomControls#RestartZoomRoomsClient", "");
                room.getControllableProperties().add(createButton("RoomControls#RestartZoomRoomsClient", "Restart", "Restarting...", 0L));
            }

            room.setTimestamp(currentTime);
        }
    }

    /**
     * Retrieve list of room devices
     *
     * @param roomId to get devices for
     * @return response JsonNode
     * @throws Exception if any error occurs
     */
    private JsonNode retrieveRoomDevices(String roomId) throws Exception {
        JsonNode roomDevices = doGet(String.format(ZOOM_DEVICES_URL, roomId), JsonNode.class);
        if (roomDevices != null && roomDevices.has("devices")) {
            return roomDevices.get("devices");
        }
        return null;
    }

    /**
     * Retrieve list of ZoomRooms metrics
     *
     * @return response JsonNode
     * @throws Exception if any error occurs
     */
    private JsonNode retrieveZoomRoomMetrics() throws Exception {
        JsonNode roomsMetrics = doGet(ZOOM_ROOM_METRICS, JsonNode.class);
        if (roomsMetrics != null && !roomsMetrics.isNull() && roomsMetrics.has("zoom_rooms")) {
            return roomsMetrics.get("zoom_rooms");
        }
        return null;
    }

    /**
     * Retrieve room settings
     *
     * @param type of settings list to get
     * @return response JsonNode
     *
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveRoomSettings(String roomId, String type) throws Exception {
        JsonNode roomSettings = doGet(String.format(ZOOM_ROOM_SETTINGS_URL, roomId) + "?setting_type=" + type, JsonNode.class);
        return roomSettings;
    }

    /**
     * Retrieve settings for an account
     *
     * @param type of settings list to get
     * @return response JsonNode
     *
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveAccountSettings(String type) throws Exception {
        JsonNode accountSettings = doGet(String.format(ZOOM_ROOM_ACCOUNT_SETTINGS_URL) + "?setting_type=" + type, JsonNode.class);
        return accountSettings;
    }

    /**
     *
     */
    private void updateAppVersion(String roomId, String deviceId, String action) throws Exception {
        Map<String, String> command = new HashMap<>();
        command.put("action", action);
        doPut(String.format(ZOOM_UPDATE_APP_VERSION, roomId, deviceId), command);
    }

    /**
     * Update Zoom Account setting
     *
     * @param setting name of the setting to update
     * @param value new value for the property
     * @param type of the setting, either alert or meeting
     * @param parentNode json parent node to use while building json request payload
     *
     * @throws Exception if a communication error occurs
     */
    private void updateAccountSettings(String setting, String value, String type, String parentNode) throws Exception {
        Map<String, Map<String, String>> request = new HashMap<>();
        Map<String, String> patch = new HashMap<>();
        patch.put(setting, value);
        request.put(parentNode, patch);
        doPatch(ZOOM_ROOM_ACCOUNT_SETTINGS_URL + "?setting_type=" + type, request, String.class);
    }

    /**
     * Update ZoomRoom setting
     *
     * @param roomId id of the room to update property for
     * @param setting name of the setting to update
     * @param value new value for the setting
     * @param type of the setting, either alert or meeting
     * @param parentNode json parent node to use while building json request payload
     *
     * @throws Exception if a communication error occurs
     */
    private void updateRoomSetting(String roomId, String setting, String value, String type, String parentNode) throws Exception {
        Map<String, Map<String, String>> request = new HashMap<>();
        Map<String, String> patch = new HashMap<>();
        patch.put(setting, value);
        request.put(parentNode, patch);
        doPatch(String.format(ZOOM_ROOM_SETTINGS_URL, roomId) + "?setting_type=" + type, request, String.class);
    }

    /**
     * TODO: /rooms returns payload like
     *         {
     *             "id": "nsQrxzFYQVagHbBVVWfa4w",
     *             "room_id": "NGM5V1sZTD6TpBljoBTnwg",
     *             "name": "Adam Stanton Test Account",
     *             "location_id": "",
     *             "status": "Available"
     *         },
     *  For all jsonRpc operations ID is needed as a room identifier, but for the rest of operations - room_id is used.
     *  This method should check caches and retrieve id(serial number) from the devices there, based on the room_id.
     */
    private String retrieveIdByRoomId (String roomId) {
        return aggregatedDevices.stream().filter(aggregatedDevice -> aggregatedDevice.getDeviceId().equals(roomId)).findFirst().map(AggregatedDevice::getSerialNumber).orElse(null);
    }

    /**
     * Restart ZoomRoom client by sending jsonRpc command
     * @param roomId id of the room to restart
     * @throws Exception if any error occurs
     */
    private void restartZoomRoomClient(String roomId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, roomId), buildRpcRequest("restart"));
    }

    /**
     * End current Zoom meeting by sending jsonRpc command
     * @param roomId id of the room to end current meeting for
     * @throws Exception if any error occurs
     */
    private void endCurrentMeeting(String roomId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, roomId), buildRpcRequest("end"));
    }

    /**
     * Leave current Zoom meeting by sending jsonRpc command
     * @param roomId id of the room make leave the meeting
     * @throws Exception if any error occurs
     */
    private void leaveCurrentMeeting(String roomId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, roomId), buildRpcRequest("leave"));
    }
}
