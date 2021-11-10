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
import com.avispl.symphony.dal.communicator.aggregator.status.RoomStatusProcessor;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
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
    /**
     *
     *
     * */
    class ZoomRoomsDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public ZoomRoomsDeviceDataLoader() {
            inProgress = true;
        }

        @Override
        public void run() {
            mainloop: while(inProgress) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    // Ignore for now
                }

                if(!inProgress){
                    break mainloop;
                }

                // next line will determine whether Zoom monitoring was paused
                updateAggregatorStatus();
                if (devicePaused) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format(
                                "Device adapter did not receive retrieveMultipleStatistics call in %s s. Statistics retrieval and device metadata retrieval is suspended.",
                                retrieveStatisticsTimeOut / 1000));
                    }
                    continue mainloop;
                }

                try {
                    if(logger.isDebugEnabled()) {
                        logger.debug("Fetching devices list");
                    }
                    fetchDevicesList();
                    if(logger.isDebugEnabled()) {
                        logger.debug("Fetched devices list: " + aggregatedDevices);
                    }
                } catch (Exception e) {
                    logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage());
                }

                if(!inProgress){
                    break mainloop;
                }

                int aggregatedDevicesCount = aggregatedDevices.size();
                if(aggregatedDevicesCount == 0 || nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                        //
                    }
                    continue mainloop;
                }

                List<AggregatedDevice> scannedDevicesList = new ArrayList<>(aggregatedDevices.values());

                for(AggregatedDevice aggregatedDevice : scannedDevicesList){
                    if(!inProgress){
                        break;
                    }
                    devicesExecutionPool.add(executorService.submit(() -> {
                        try {
                            populateDeviceDetails(aggregatedDevice.getDeviceId());
                        } catch (Exception e) {
                            logger.error(String.format("Exception during Zoom Room '%s' data processing.", aggregatedDevice.getDeviceName()), e);
                        }
                    }));
                }

                do {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e){
                        if(!inProgress){
                            break;
                        }
                    }
                    devicesExecutionPool.removeIf(Future::isDone);
                } while (!devicesExecutionPool.isEmpty());

                // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                // launches devices detailed statistics collection
                nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

                if (logger.isDebugEnabled()) {
                    logger.debug("Finished collecting devices statistics cycle at " + new Date());
                }
            }
            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            inProgress = false;
        }
    }

    private static final String BASE_ZOOM_URL = "v2";
    private static final String ZOOM_ROOMS_URL = "rooms?page_size=5000";
    private static final String ZOOM_DEVICES_URL = "rooms/%s/devices?page_size=5000"; // Requires room Id
    private static final String ZOOM_ROOM_SETTINGS_URL = "/rooms/%s/settings"; // Requires room Id
    private static final String ZOOM_ROOM_ACCOUNT_SETTINGS_URL = "rooms/account_settings";
    private static final String ZOOM_ROOM_METRICS = "metrics/zoomrooms/%s";
    private static final String ZOOM_ROOM_LOCATIONS = "rooms/locations?page_size=5000";
    private static final String ZOOM_UPDATE_APP_VERSION = "/rooms/%s/devices/%s/app_version";

    private static final String ZOOM_ROOM_CLIENT_RPC = "/rooms/%s/zrclient";

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    /**
     * Devices this aggregator is responsible for
     */
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();

    private Properties adapterProperties;

    private String zoomRoomLocations;
    private String zoomRoomTypes; // ZoomRoom, SchedulingDisplayOnly, DigitalSignageOnly
    private String devicesProperties;
    private String aggregatorProperties;
    private String authorizationToken;

    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * If ignored if device list is not yet retrieved or the cached device list is empty {@link ZoomRoomsAggregatorCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private static long nextDevicesCollectionIterationTimestamp;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Aggregator inactivity timeout. If the {@link ZoomRoomsAggregatorCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link ZoomRoomsAggregatorCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    private static ExecutorService executorService;
    private List<Future> devicesExecutionPool = new ArrayList<>();
    private ZoomRoomsDeviceDataLoader deviceDataLoader;

    /**
     * Retrieves {@code {@link #zoomRoomLocations}}
     *
     * @return value of {@link #zoomRoomLocations}
     */
    public String getZoomRoomLocations() {
        return zoomRoomLocations;
    }

    /**
     * Sets {@code zoomRoomLocations}
     *
     * @param zoomRoomLocations the {@code java.lang.String} field
     */
    public void setZoomRoomLocations(String zoomRoomLocations) {
        this.zoomRoomLocations = zoomRoomLocations;
    }

    /**
     * Retrieves {@code {@link #zoomRoomTypes}}
     *
     * @return value of {@link #zoomRoomTypes}
     */
    public String getZoomRoomTypes() {
        return zoomRoomTypes;
    }

    /**
     * Sets {@code zoomRoomTypes}, removes whitespaces to make csv line ready for use as a query string parameter
     *
     * @param zoomRoomTypes the {@code java.lang.String} field
     */
    public void setZoomRoomTypes(String zoomRoomTypes) {
        this.zoomRoomTypes = zoomRoomTypes.replaceAll(" ", "");
    }

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
                    throw new IllegalArgumentException("Invalid property name provided: " + property);
                }
                String settingValue;
                if (property.endsWith(ZoomRoomsSetting.BatteryPercentage.name())) {
                    // BatteryPercentage is a Numeric controllable property
                    settingValue = String.valueOf(value);
                } else {
                    settingValue = "0".equals(String.valueOf(value)) ? "false" : "true";
                }
                updateRoomSetting(roomId, setting.getSettingName(), settingValue, setting.getSettingType(), setting.getParentNode());
                return;
            } else if (property.startsWith("AccountMeetingSettings#") || property.startsWith("AccountAlertSettings#")) {
                Setting setting = Setting.fromString(ZoomRoomsSetting.valueOf(property.split("#")[1]).toString());
                if (setting == null) {
                    throw new IllegalArgumentException("Invalid property name provided: " + property);
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
        setBaseUri(BASE_ZOOM_URL);
        authorizationToken = getPassword();
        super.internalInit();
        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();
        executorService = Executors.newFixedThreadPool(8);
        executorService.submit(deviceDataLoader = new ZoomRoomsDeviceDataLoader());
        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();
    }

    /** {@inheritDoc} */
    @Override
    protected void internalDestroy() {
        if (deviceDataLoader != null) {
            deviceDataLoader.stop();
            deviceDataLoader = null;
        }

        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }

        devicesExecutionPool.forEach(future -> future.cancel(true));
        devicesExecutionPool.clear();

        aggregatedDevices.clear();
        super.internalDestroy();
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
        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
        updateValidRetrieveStatisticsTimestamp();

        aggregatedDevices.values().forEach(aggregatedDevice -> aggregatedDevice.setTimestamp(System.currentTimeMillis()));
        return new ArrayList<>(aggregatedDevices.values());
    }

    /**
     * Retrieve Zoom Rooms devices with basic information and save it to {@link #aggregatedDevices} collection
     * Filter Zoom Rooms based on location id.
     * In order to make it more user-friendly, it is expected that {@link #zoomRoomLocations} will contain
     * csv list of Location Names, e.g "Country/Region1", "State1" etc.
     *
     * TODO: check location hierarchy, it may be necessary to check ParentLocationId in order to retrieve the right one.
     *
     * @throws Exception if a communication error occurs
     */
    private void fetchDevicesList() throws Exception {
        if (aggregatedDevices.size() > 0 && validDeviceMetaDataRetrievalPeriodTimestamp > System.currentTimeMillis()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("General devices metadata retrieval is in cooldown. %s seconds left",
                        (validDeviceMetaDataRetrievalPeriodTimestamp - System.currentTimeMillis()) / 1000));
            }
            return;
        }

        List<String> supportedLocationIds = new ArrayList<>();
        if (!StringUtils.isNullOrEmpty(zoomRoomLocations)) {
            JsonNode roomLocations = retrieveZoomRoomLocations();
            if(roomLocations != null && roomLocations.isArray()) {
                for (JsonNode roomLocation : roomLocations) {
                    Map<String, String> location = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(location, roomLocation, "RoomLocation");
                    if (zoomRoomLocations.contains(location.get("Location#Name"))) {
                        supportedLocationIds.add(location.get("Location#ID"));
                    }
                }
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Locations filter is not provided, skipping room filtering by location.");
            }
        }

        List<AggregatedDevice> zoomRooms = aggregatedDeviceProcessor.extractDevices(retrieveZoomRooms());
        zoomRooms.forEach(aggregatedDevice -> {
            if(StringUtils.isNullOrEmpty(zoomRoomLocations) || !StringUtils.isNullOrEmpty(zoomRoomLocations) && supportedLocationIds.contains(aggregatedDevice.getProperties().get("LocationId"))) {
                if (aggregatedDevices.containsKey(aggregatedDevice.getDeviceId())) {
                    aggregatedDevices.get(aggregatedDevice.getDeviceId()).setDeviceOnline(aggregatedDevice.getDeviceOnline());
                } else {
                    aggregatedDevices.put(aggregatedDevice.getDeviceId(), aggregatedDevice);
                }
            } else {
                aggregatedDevices.remove(aggregatedDevice.getDeviceId());
            }
        });

        if (zoomRooms.isEmpty()) {
            // If all the devices were not populated for any specific reason (no devices available, filtering, etc)
            aggregatedDevices.clear();
        }

        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
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
        StringBuilder queryString = new StringBuilder();
        if (!StringUtils.isNullOrEmpty(zoomRoomTypes)) {
            queryString.append("&type=").append(zoomRoomTypes);
        }
        return doGet(ZOOM_ROOMS_URL + queryString.toString(), JsonNode.class);
    }

    /**
     * Populate ZoomRooms with properties: metrics, devices, controls etc.
     *
     * @param roomId Id of zoom room
     * @throws Exception if any error occurs
     */
    private void populateDeviceDetails(String roomId) throws Exception {
        JsonNode roomMetrics = retrieveZoomRoomMetrics(roomId);

        HashMap<String, String> roomProperties = new HashMap<>();
        if (roomMetrics != null) {
            Map<String, String> roomIssues = RoomStatusProcessor.processIssuesList(roomMetrics.get("issues"));

            roomIssues.forEach((key, value) -> roomProperties.put("RoomStatus#" + key, value));

            aggregatedDeviceProcessor.applyProperties(roomProperties, roomMetrics, "ZoomRoomMetrics");
        }

        Map<String, String> properties = new HashMap<>();
        List<AdvancedControllableProperty> controllableProperties = new ArrayList<>();

        properties.putAll(roomProperties);

        Map<String, String> settingsProperties = new HashMap<>();
        List<AdvancedControllableProperty> settingsControls = new ArrayList<>();

        JsonNode meetingSettings = retrieveRoomSettings(roomId, "meeting");
        aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, meetingSettings, "RoomMeetingSettings");
        JsonNode alertSettings = retrieveRoomSettings(roomId, "alert");
        aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, alertSettings, "RoomAlertSettings");

        /** TODO: this segment will be removed and moved completely to yml config after SYAL-625 is fixed */
        if (alertSettings != null) {
            JsonNode notificationSettings = alertSettings.get("notification");
            if(notificationSettings != null) {
                JsonNode batteryPercentageValue = notificationSettings.get("battery_percentage");
                if(batteryPercentageValue != null) {
                    int percentageValue = batteryPercentageValue.asInt();
                    String batteryPercentagePropertyName = "RoomControlsAlertSettings#BatteryPercentage";
                    settingsProperties.put(batteryPercentagePropertyName, String.valueOf(percentageValue));

                    AdvancedControllableProperty batteryPercentage = new AdvancedControllableProperty();
                    batteryPercentage.setType(new AdvancedControllableProperty.Numeric());
                    batteryPercentage.setTimestamp(new Date());
                    batteryPercentage.setValue(percentageValue);
                    batteryPercentage.setName(batteryPercentagePropertyName);
                    settingsControls.add(batteryPercentage);
                }
            }
        }
        /** TODO */

//            // if the property isn't there - we should not display this control and its label
        settingsControls.removeIf(advancedControllableProperty -> {
            String value = String.valueOf(advancedControllableProperty.getValue());
            if(StringUtils.isNullOrEmpty(value)) {
                settingsProperties.remove(advancedControllableProperty.getName());
                return true;
            }
            return false;
        });

        properties.putAll(settingsProperties);
        controllableProperties.addAll(settingsControls);
        JsonNode devices = retrieveRoomDevices(roomId);
        if (devices != null && devices.isArray()) {
            for (JsonNode deviceNode : devices) {
                Map<String, String> roomDeviceProperties = new HashMap<>();
                aggregatedDeviceProcessor.applyProperties(roomDeviceProperties, deviceNode, "RoomDevice");
                for (Map.Entry<String, String> entry : roomDeviceProperties.entrySet()) {
                    properties.put(String.format("ZoomRoomDevice_%s_%s", roomDeviceProperties.get("Info#ID"), entry.getKey()), entry.getValue());
                }
            }
        }

        String roomStatus = properties.get("Metrics#RoomStatus");
        if (!StringUtils.isNullOrEmpty(roomStatus) && roomStatus.equals("InMeeting")) {
            properties.put("RoomControls#EndCurrentMeeting", "");
            controllableProperties.add(createButton("RoomControls#EndCurrentMeeting", "End", "Ending...", 0L));

            properties.put("RoomControls#LeaveCurrentMeeting", "");
            controllableProperties.add(createButton("RoomControls#LeaveCurrentMeeting", "Leave", "Leaving...", 0L));
        }

        if (!StringUtils.isNullOrEmpty(roomStatus) && !roomStatus.equals("Offline")) {
            properties.put("RoomControls#RestartZoomRoomsClient", "");
            controllableProperties.add(createButton("RoomControls#RestartZoomRoomsClient", "Restart", "Restarting...", 0L));
        }

        AggregatedDevice aggregatedZoomRoomDevice = aggregatedDevices.get(roomId);
        aggregatedZoomRoomDevice.setProperties(properties);
        aggregatedZoomRoomDevice.setControllableProperties(controllableProperties);
        aggregatedZoomRoomDevice.setTimestamp(System.currentTimeMillis());
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
     * Retrieve list of ZoomRooms metric by roomId
     *
     * @return response JsonNode
     * @throws Exception if any error occurs
     */
    private JsonNode retrieveZoomRoomMetrics(String roomId) throws Exception {
        JsonNode roomsMetrics = doGet(String.format(ZOOM_ROOM_METRICS, roomId), JsonNode.class);
        if (roomsMetrics != null && !roomsMetrics.isNull()) {
            return roomsMetrics;
        }
        return null;
    }

    /**
     * Retrieve list of ZoomRooms locations
     *
     * @return response JsonNode
     * @throws Exception if any error occurs
     */
    private JsonNode retrieveZoomRoomLocations() throws Exception {
        JsonNode roomsMetrics = doGet(ZOOM_ROOM_LOCATIONS, JsonNode.class);
        if (roomsMetrics != null && !roomsMetrics.isNull() && roomsMetrics.has("locations")) {
            return roomsMetrics.get("locations");
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
     * For all jsonRpc operations ID is needed as a room identifier, but for the rest of operations - room_id is used.
     * This method should check caches and retrieve id(serial number) from the devices there, based on the room_id.
     *
     * @param roomId id of a ZoomRoom
     * @return String value of serial number (room id in a system)
     */
    private String retrieveIdByRoomId (String roomId) {
        AggregatedDevice room = aggregatedDevices.get(roomId);
        if (room != null) {
            return room.getSerialNumber();
        }
        return null;
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link ZoomRoomsAggregatorCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
    }

    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
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
