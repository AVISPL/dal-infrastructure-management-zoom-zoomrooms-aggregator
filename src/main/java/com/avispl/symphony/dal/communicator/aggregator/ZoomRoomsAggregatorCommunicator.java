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
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
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

    private String devicesProperties;
    private String aggregatorProperties;
    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    private Properties adapterProperties;

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

    public ZoomRoomsAggregatorCommunicator() throws IOException {
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
    }

    @Override
    protected void authenticate() throws Exception {
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String roomId = controllableProperty.getDeviceId();
        String property = controllableProperty.getProperty();

        if(!StringUtils.isNullOrEmpty(roomId) && !StringUtils.isNullOrEmpty(property)){
            if(property.startsWith("RoomSettings#")) {
                String setting = ZoomRoomsSetting.valueOf(property.split("#")[1]).name();
                String value = "0".equals(property) ? "false" : "true";
                updateRoomSetting(roomId, setting, value, ""); // TODO specify setting type
                return;
            } else {
                switch (property) {
                    case "RoomControls#LeaveCurrentMeeting":
                        leaveCurrentMeeting(roomId);
                        break;
                    case "RoomControls#EndCurrentMeeting":
                        endCurrentMeeting(roomId);
                        break;
                    case "RoomControls#RestartZoomRoomsClient":
                        restartZoomRoomClient(roomId);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        Map<String, String> statistics = new HashMap<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        List<AdvancedControllableProperty> accountSettingsControls = new ArrayList<>();
        aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings(), "AccountSettings");

        statistics.put("AdapterVersion", adapterProperties.getProperty("mock.aggregator.version"));
        statistics.put("AdapterBuildDate", adapterProperties.getProperty("mock.aggregator.build.date"));

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(accountSettingsControls);
        return Collections.singletonList(extendedStatistics);
    }

    @Override
    protected void internalInit() throws Exception {
        authorizationToken = getPassword();
        setBaseUri(BASE_ZOOM_URL);
        super.internalInit();
    }

    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();
        restTemplate.getMessageConverters().add(new Jaxb2RootElementHttpMessageConverter());
        return restTemplate;
    }

    /**
     * {@inheritDoc}
     * Zoom api endpoint does not have ICMP available, so this workaround is needed to provide
     * ping latency information to Symphony
     */
    @Override
    public int ping() throws Exception {
        if(isInitialized()) {
            long pingResultTotal = 0L;

            for (int i = 0; i < this.getPingAttempts(); i++) {
                long startTime = System.currentTimeMillis();

                try (Socket puSocketConnection = new Socket(this.getHost(), this.getPort())) {
                    puSocketConnection.setSoTimeout(this.getPingTimeout());

                    if (puSocketConnection.isConnected()) {
                        long endTime = System.currentTimeMillis();
                        long pingResult = endTime - startTime;
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

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", "bearer " + authorizationToken);
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
        List<AggregatedDevice> devices = aggregatedDeviceProcessor.extractDevices(retrieveZoomRooms());
        retrieveRoomDevicesProperties(devices);
        return devices;
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
                .collect(Collectors.toList());
    }

    /**
     *
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
     *
     */
    private JsonNode retrieveZoomRooms() throws Exception {
        return doGet(ZOOM_ROOMS_URL, JsonNode.class);
    }

    /**
     *
     */
    private void retrieveRoomDevicesProperties(List<AggregatedDevice> rooms) throws Exception {
        JsonNode roomMetrics = retrieveZoomRoomMetrics();
        Map<String, Map<String, String>> roomMetricsProperties = new HashMap<>();
        for(JsonNode roomMetric: roomMetrics) {
            HashMap<String, String> roomProperties = new HashMap<>();
            aggregatedDeviceProcessor.applyProperties(roomProperties, roomMetric, "ZoomRoomMetrics");
            roomMetricsProperties.put(roomProperties.get("id"), roomProperties);
        }

        Long currentTime = new Date().getTime();
        for(AggregatedDevice room: rooms) {
            String roomId = room.getDeviceId();
            room.getProperties().putAll(roomMetricsProperties.get(roomId));

            Map<String, String> settingsProperties = new HashMap<>();
            List<AdvancedControllableProperty> settingsControls = new ArrayList<>();

            JsonNode meetingSettings = retrieveRoomMeetingSettings(roomId);
            aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, meetingSettings, "RoomMeetingSettings");
//            JsonNode alertSettings = retrieveRoomAlertSettings(roomId);
//            aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, meetingSettings, "RoomAlertSettings");

            room.getProperties().putAll(settingsProperties);
            room.getControllableProperties().addAll(settingsControls);
            JsonNode devices = retrieveRoomDevices(roomId);
            if(devices != null && devices.isArray()) {
                int i = 0;
                for(JsonNode deviceNode: devices) {
                    i++;
                    Map<String, String> properties = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(properties, deviceNode, "RoomDevice");
                    // TODO add properties to aggregated device

                    for(Map.Entry<String, String> entry: properties.entrySet()){
                        room.getProperties().put(String.format("RoomDevice_%s:%s", i, entry.getKey()), entry.getValue());
                    }
                }
            }

            if(room.getProperties().get("Metrics#RoomStatus").equals("InMeeting")) {
                room.getProperties().put("RoomControls#EndCurrentMeeting", "");
                room.getControllableProperties().add(createButton("RoomControls#EndCurrentMeeting", "End", "Ending...", 0L));

                room.getProperties().put("RoomControls#LeaveCurrentMeeting", "");
                room.getControllableProperties().add(createButton("RoomControls#LeaveCurrentMeeting", "Leave", "Leaving...", 0L));
            }

            if(!room.getProperties().get("Metrics#RoomStatus").equals("Offline")) {
                room.getProperties().put("RoomControls#RestartZoomRoomsClient", "");
                room.getControllableProperties().add(createButton("RoomControls#RestartZoomRoomsClient", "Restart", "Restarting...", 0L));
            }

            room.setTimestamp(currentTime);
        }
    }

    /**
     *
     */
    private JsonNode retrieveRoomDevices(String roomId) throws Exception {
        JsonNode roomDevices = doGet(String.format(ZOOM_DEVICES_URL, roomId), JsonNode.class);
        if(roomDevices != null && roomDevices.has("devices")) {
            return roomDevices.get("devices");
        }
        return null;
    }

    /**
     *
     */
    private JsonNode retrieveZoomRoomMetrics() throws Exception {
        JsonNode roomsMetrics = doGet(ZOOM_ROOM_METRICS, JsonNode.class);
        if(roomsMetrics != null && !roomsMetrics.isNull() && roomsMetrics.has("zoom_rooms"))
        {
            return roomsMetrics.get("zoom_rooms");
        }
        return null;
    }

    /**
     *
     */
    private JsonNode retrieveRoomAlertSettings(String roomId) throws Exception {
        // TODO type -> alert, meeting, signage
        JsonNode roomSettings = doGet(String.format(ZOOM_ROOM_SETTINGS_URL, roomId) + "?setting_type=alert", JsonNode.class);
        return roomSettings;
    }

    /**
     *
     */
    private JsonNode retrieveRoomMeetingSettings(String roomId) throws Exception {
        // TODO type -> alert, meeting, signage
        JsonNode roomSettings = doGet(String.format(ZOOM_ROOM_SETTINGS_URL, roomId) + "?setting_type=meeting", JsonNode.class);
        return roomSettings;
    }

    /**
     *
     */
    private JsonNode retrieveAccountSettings() throws Exception {
        // TODO type -> alert, meeting, signage
        JsonNode accountSettings = doGet(String.format(ZOOM_ROOM_ACCOUNT_SETTINGS_URL), JsonNode.class);
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
     *
     */
    private void updateAccountSettings(String setting, String value) {
    }

    /**
     * @param type alert, meeting or signage
     */
    private void updateRoomSetting(String roomId, String setting, String value, String type) throws Exception {
        Map<String, Map<String, String>> request = new HashMap<>();
        Map<String, String> patch = new HashMap<>();
        patch.put(setting, value);
        request.put("zoom_rooms", patch);
        String response = doPatch(String.format(ZOOM_ROOM_SETTINGS_URL, roomId) + "?setting_type=meeting", request, String.class);
        response.trim();
    }

    /**
     *
     */
    private void restartZoomRoomClient(String roomId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, roomId), buildRpcRequest("restart"));
    }

    /**
     *
     */
    private void endCurrentMeeting(String roomId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, roomId), buildRpcRequest("end"));
    }

    /**
     *
     */
    private void leaveCurrentMeeting(String roomId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, roomId), buildRpcRequest("leave"));
    }
}
