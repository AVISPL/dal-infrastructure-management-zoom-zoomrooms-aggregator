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
import com.avispl.symphony.api.dal.error.CommandFailureException;
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

import static com.avispl.symphony.dal.communicator.aggregator.properties.PropertyNameConstants.*;

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
     * Process that is running constantly and triggers collecting data from Zoom API endpoints, based on the given timeouts and thresholds.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class ZoomRoomsDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public ZoomRoomsDeviceDataLoader() {
            inProgress = true;
        }

        @Override
        public void run() {
            mainloop:
            while (inProgress) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    // Ignore for now
                }

                if (!inProgress) {
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
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetching devices list");
                    }
                    fetchDevicesList();

                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetched devices list: " + aggregatedDevices);
                    }
                } catch (Exception e) {
                    logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage());
                }

                if (!inProgress) {
                    break mainloop;
                }

                int aggregatedDevicesCount = aggregatedDevices.size();
                if (aggregatedDevicesCount == 0) {
                    continue mainloop;
                }

                while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                        //
                    }
                }

                try {
                    // The following request collect all the information, so in order to save number of requests, which is
                    // daily limited for certain APIs, we need to request them once per monitoring cycle.
                    retrieveZoomRoomMetrics();
                } catch (Exception e) {
                    logger.error("Error occurred during ZoomRooms metrics retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage());
                }

                for (AggregatedDevice aggregatedDevice : aggregatedDevices.values()) {
                    if (!inProgress) {
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
                    } catch (InterruptedException e) {
                        if (!inProgress) {
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
    private static final String ZOOM_DEVICES_URL = "rooms/%s/devices?page_size=5000"; // Requires room Id // TODO: Configurable page size
    private static final String ZOOM_ROOM_SETTINGS_URL = "/rooms/%s/settings"; // Requires room Id
    private static final String ZOOM_ROOM_ACCOUNT_SETTINGS_URL = "rooms/account_settings";
    private static final String ZOOM_ROOMS_METRICS = "metrics/zoomrooms?page_size=5000"; // TODO: Configurable page size
    private static final String ZOOM_ROOM_LOCATIONS = "rooms/locations?page_size=5000"; // TODO: Configurable page size
    private static final String ZOOM_USER_DETAILS = "/users/%s";

    private static final String ZOOM_ROOM_CLIENT_RPC = "/rooms/%s/zrclient";
    private static final String ZOOM_ROOM_CLIENT_RPC_MEETINGS = "/rooms/%s/meetings";

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    /**
     * Devices this aggregator is responsible for
     */
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Map<String, String>> zoomRoomsMetricsData = new ConcurrentHashMap<>();

    private Properties adapterProperties;

    private String zoomRoomLocations;
    private String zoomRoomTypes; // ZoomRoom, SchedulingDisplayOnly, DigitalSignageOnly
    private String devicesProperties;
    private String aggregatorProperties;
    private String authorizationToken;

    /** Whether service is running. */
    private volatile boolean serviceRunning;

    /**
     * Triggers visibility of Room property groups:
     * RoomControlsAlertSettings, RoomControlsMeetingSettings
     * */
    private boolean displayRoomSettings;

    /**
     * Triggers visibility of Aggregator property groups:
     * AccountAlertSettings, AccountMeetingSettings
     * */
    private boolean displayAccountSettings;

    /**
     * If the {@link ZoomRoomsAggregatorCommunicator#deviceMetaDataRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultMetaDataTimeout = 60 * 1000 / 2;

    /**
     * If the {@link ZoomRoomsAggregatorCommunicator#metricsRetrievalTimeout} is set to a value that is too small -
     * devices metrics will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultMetricsTimeout = 60 * 1000 / 2;

    /**
     * If the {@link ZoomRoomsAggregatorCommunicator#roomUserDetailsRetrievalTimeout} is set to a value that is too small -
     * devices user details will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultUserDetailsTimeout = 60 * 1000 / 2;

    /**
     * If the {@link ZoomRoomsAggregatorCommunicator#roomSettingsRetrievalTimeout} is set to a value that is too small -
     * devices settings will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultRoomSettingsTimeout = 60 * 1000 / 2;

    /**
     * If the {@link ZoomRoomsAggregatorCommunicator#roomDevicesRetrievalTimeout} is set to a value that is too small -
     * room devices will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultRoomDevicesTimeout = 60 * 1000 / 2;

    /**
     * Device metadata retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceMetaDataRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Device metrics retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long metricsRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Room user details retrieval timeout. Info is retrieved once during this time period.
     */
    private long roomUserDetailsRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Room settings retrieval timeout. Info is retrieved once during this time period.
     */
    private long roomSettingsRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Registered room devices retrieval timeout. Info is retrieved once during this time period.
     */
    private long roomDevicesRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link ZoomRoomsAggregatorCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;

    /**
     * Time period within which the device metrics (dynamic information) cannot be refreshed.
     * Ignored if metrics data is not yet retrieved
     */
    private volatile long validMetricsDataRetrievalPeriodTimestamp;

    /**
     * Map of roomUserId:timestamp within which the room user details cannot be refreshed.
     * Ignored if the data is not yet retrieved for the room
     */
    private ConcurrentHashMap<String, Long>  validUserDetailsDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Map of roomId:timestamp within which the room settings cannot be refreshed.
     * Ignored if the data is not yet retrieved for the room
     */
    private ConcurrentHashMap<String, Long>  validRoomSettingsDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Map of roomId:timestamp within which the registered room devices details cannot be refreshed.
     * Ignored if the data is not yet retrieved for the room
     */
    private ConcurrentHashMap<String, Long>  validRoomDevicesDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

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
     * Retrieves {@code {@link #deviceMetaDataRetrievalTimeout }}
     *
     * @return value of {@link #deviceMetaDataRetrievalTimeout}
     */
    public long getDeviceMetaDataRetrievalTimeout() {
        return deviceMetaDataRetrievalTimeout;
    }

    /**
     * Sets {@code deviceMetaDataInformationRetrievalTimeout}
     *
     * @param deviceMetaDataRetrievalTimeout the {@code long} field
     */
    public void setDeviceMetaDataRetrievalTimeout(long deviceMetaDataRetrievalTimeout) {
        this.deviceMetaDataRetrievalTimeout = deviceMetaDataRetrievalTimeout;
    }

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
     * Retrieves {@code {@link #metricsRetrievalTimeout}}
     *
     * @return value of {@link #metricsRetrievalTimeout}
     */
    public long getMetricsRetrievalTimeout() {
        return metricsRetrievalTimeout;
    }

    /**
     * Sets {@code metricsRetrievalTimeout}
     *
     * @param metricsRetrievalTimeout the {@code long} field
     */
    public void setMetricsRetrievalTimeout(long metricsRetrievalTimeout) {
        this.metricsRetrievalTimeout = metricsRetrievalTimeout;
    }

    /**
     * Retrieves {@code {@link #roomUserDetailsRetrievalTimeout }}
     *
     * @return value of {@link #roomUserDetailsRetrievalTimeout}
     */
    public long getRoomUserDetailsRetrievalTimeout() {
        return roomUserDetailsRetrievalTimeout;
    }

    /**
     * Sets {@code userDetailsRetrievalTimeout}
     *
     * @param roomUserDetailsRetrievalTimeout the {@code long} field
     */
    public void setRoomUserDetailsRetrievalTimeout(long roomUserDetailsRetrievalTimeout) {
        this.roomUserDetailsRetrievalTimeout = roomUserDetailsRetrievalTimeout;
    }

    /**
     * Retrieves {@code {@link #roomSettingsRetrievalTimeout}}
     *
     * @return value of {@link #roomSettingsRetrievalTimeout}
     */
    public long getRoomSettingsRetrievalTimeout() {
        return roomSettingsRetrievalTimeout;
    }

    /**
     * Sets {@code roomSettingsRetrievalTimeout}
     *
     * @param roomSettingsRetrievalTimeout the {@code long} field
     */
    public void setRoomSettingsRetrievalTimeout(long roomSettingsRetrievalTimeout) {
        this.roomSettingsRetrievalTimeout = roomSettingsRetrievalTimeout;
    }

    /**
     * Retrieves {@code {@link #roomDevicesRetrievalTimeout}}
     *
     * @return value of {@link #roomDevicesRetrievalTimeout}
     */
    public long getRoomDevicesRetrievalTimeout() {
        return roomDevicesRetrievalTimeout;
    }

    /**
     * Sets {@code roomDevicesRetrievalTimeout}
     *
     * @param roomDevicesRetrievalTimeout the {@code long} field
     */
    public void setRoomDevicesRetrievalTimeout(long roomDevicesRetrievalTimeout) {
        this.roomDevicesRetrievalTimeout = roomDevicesRetrievalTimeout;
    }

    /**
     * Retrieves {@code {@link #displayRoomSettings}}
     *
     * @return value of {@link #displayRoomSettings}
     */
    public boolean isDisplayRoomSettings() {
        return displayRoomSettings;
    }

    /**
     * Sets {@code displayRoomSettings}
     *
     * @param displayRoomSettings the {@code boolean} field
     */
    public void setDisplayRoomSettings(boolean displayRoomSettings) {
        this.displayRoomSettings = displayRoomSettings;
    }

    /**
     * Retrieves {@code {@link #displayAccountSettings}}
     *
     * @return value of {@link #displayAccountSettings}
     */
    public boolean isDisplayAccountSettings() {
        return displayAccountSettings;
    }

    /**
     * Sets {@code displayAccountSettings}
     *
     * @param displayAccountSettings the {@code boolean} field
     */
    public void setDisplayAccountSettings(boolean displayAccountSettings) {
        this.displayAccountSettings = displayAccountSettings;
    }

    /**
     * Build an instance of ZoomRoomsAggregatorCommunicator
     * Setup aggregated devices processor, initialize adapter properties
     *
     * @throws IOException if unable to locate mapping ymp file or properties file
     */
    public ZoomRoomsAggregatorCommunicator() throws IOException {
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void authenticate() throws Exception {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String roomId = controllableProperty.getDeviceId();
        String property = controllableProperty.getProperty();
        Object value = controllableProperty.getValue();

        if (!StringUtils.isNullOrEmpty(roomId) && !StringUtils.isNullOrEmpty(property)) {
            boolean controlValidated = false;

            try {
                if (property.startsWith(ROOM_CONTROLS_MEETING_SETTINGS_GROUP) || property.startsWith(ROOM_CONTROLS_ALERT_SETTINGS_GROUP)) {
                    Setting setting = Setting.fromString(ZoomRoomsSetting.valueOf(property.split("#")[1]).toString());
                    if (setting == null) {
                        throw new IllegalArgumentException("Invalid property name provided: " + property);
                    }
                    String settingValue;
                    if (property.endsWith(ZoomRoomsSetting.BatteryPercentage.name())) {
                        // BatteryPercentage is a Numeric controllable property
                        settingValue = String.valueOf(value);
                    } else {
                        settingValue = normalizeSettingData(value);
                    }
                    updateRoomSetting(roomId, setting.getSettingName(), settingValue, setting.getSettingType(), setting.getParentNode());
                    controlValidated = true;
                    return;
                } else if (property.startsWith(ACCOUNT_CONTROLS_MEETING_SETTINGS_GROUP) || property.startsWith(ACCOUNT_CONTROLS_ALERT_SETTINGS_GROUP)) {
                    Setting setting = Setting.fromString(ZoomRoomsSetting.valueOf(property.split("#")[1]).toString());
                    if (setting == null) {
                        throw new IllegalArgumentException("Invalid property name provided: " + property);
                    }
                    String settingValue = normalizeSettingData(value);
                    updateAccountSettings(setting.getSettingName(), settingValue, setting.getSettingType(), setting.getParentNode());
                    controlValidated = true;
                    return;
                } else {
                    String id = retrieveIdByRoomId(roomId);
                    switch (property) {
                        case LEAVE_CURRENT_MEETING_CONTROL:
                            leaveCurrentMeeting(id);
                            controlValidated = true;
                            break;
                        case END_CURRENT_MEETING_CONTROL:
                            endCurrentMeeting(id);
                            controlValidated = true;
                            break;
                        case RESTART_ZOOM_ROOMS_CLIENT_CONTROL:
                            restartZoomRoomClient(id);
                            controlValidated = true;
                            break;
                        case START_ROOM_PMI_CONTROL:
                            joinRoomPMI(id);
                            controlValidated = true;
                        default:
                            break;
                    }
                }
            } finally {
                if (controlValidated) {
                    updateCachedControllablePropertyValue(roomId, property, String.valueOf(value));
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : controllablePropertyList) {
            controlProperty(controllableProperty);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        Map<String, String> statistics = new HashMap<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        List<AdvancedControllableProperty> accountSettingsControls = new ArrayList<>();
        if(displayAccountSettings) {
            aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings("meeting"), "AccountMeetingSettings");
            aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings("alert"), "AccountAlertSettings");

//        // if the property isn't there - we should not display this control and its label
            accountSettingsControls.removeIf(advancedControllableProperty -> {
                String value = String.valueOf(advancedControllableProperty.getValue());
                if (StringUtils.isNullOrEmpty(value)) {
                    statistics.remove(advancedControllableProperty.getName());
                    return true;
                }
                return false;
            });
        }

        statistics.put("AdapterVersion", adapterProperties.getProperty("mock.aggregator.version"));
        statistics.put("AdapterBuildDate", adapterProperties.getProperty("mock.aggregator.build.date"));

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(accountSettingsControls);
        return Collections.singletonList(extendedStatistics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void internalInit() throws Exception {
        setBaseUri(BASE_ZOOM_URL);
        authorizationToken = getPassword();
        super.internalInit();
        executorService = Executors.newFixedThreadPool(8);
        executorService.submit(deviceDataLoader = new ZoomRoomsDeviceDataLoader());
        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();

        serviceRunning = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void internalDestroy() {
        serviceRunning = false;

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
        zoomRoomsMetricsData.clear();
        validUserDetailsDataRetrievalPeriodTimestamps.clear();
        validRoomDevicesDataRetrievalPeriodTimestamps.clear();
        validRoomSettingsDataRetrievalPeriodTimestamps.clear();
        super.internalDestroy();
    }

    /**
     * {@inheritDoc}
     * <p>
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

    /**
     * {@inheritDoc}
     */
    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", "bearer " + authorizationToken);
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    /**
     * {@inheritDoc}
     */
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
     * <p>
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
        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis() + Math.max(defaultMetaDataTimeout, deviceMetaDataRetrievalTimeout);

        List<String> supportedLocationIds = new ArrayList<>();
        if (!StringUtils.isNullOrEmpty(zoomRoomLocations)) {
            JsonNode roomLocations = retrieveZoomRoomLocations();
            if (roomLocations != null && roomLocations.isArray()) {
                for (JsonNode roomLocation : roomLocations) {
                    Map<String, String> location = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(location, roomLocation, "RoomLocation");
                    if (zoomRoomLocations.contains(location.get(LOCATION_NAME))) {
                        supportedLocationIds.add(location.get(LOCATION_ID));
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
            Map<String, String> properties = aggregatedDevice.getProperties();
            if (StringUtils.isNullOrEmpty(zoomRoomLocations) || !StringUtils.isNullOrEmpty(zoomRoomLocations) && supportedLocationIds.contains(properties.get(LOCATION_ID_PROPERTY))) {
                if (aggregatedDevices.containsKey(aggregatedDevice.getDeviceId())) {
                    aggregatedDevices.get(aggregatedDevice.getDeviceId()).setDeviceOnline(aggregatedDevice.getDeviceOnline());
                } else {
                    aggregatedDevices.put(aggregatedDevice.getDeviceId(), aggregatedDevice);
                }
            } else {
                aggregatedDevices.remove(aggregatedDevice.getDeviceId());
            }

            properties.remove(LOCATION_ID_PROPERTY);
        });

        if (zoomRooms.isEmpty()) {
            // If all the devices were not populated for any specific reason (no devices available, filtering, etc)
            aggregatedDevices.clear();
        }

        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
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
    private Map<String, Object> buildRpcRequest(String method) {
        Map<String, Object> command = new HashMap<>();
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
        return doGetWithRetry(ZOOM_ROOMS_URL + queryString.toString());
    }

    /**
     * Retrieve ZoomRooms user details
     *
     * @param userId id of a room user
     * @return response JsonNode
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveUserDetails(String userId) throws Exception {
        return doGetWithRetry(String.format(ZOOM_USER_DETAILS, userId));
    }

    /**
     * If addressed too frequently, Zoom API may respond with 429 code, meaning that the call rate per second was reached.
     * Normally it would rarely happen due to the request rate limit, but when it does happen - adapter must retry the
     * attempts of retrieving needed information. This method retries up to 10 times with 500ms timeout in between
     *
     * @param url to retrieve data from
     * @return JsonNode response body
     *
     * @throws Exception if a communication error occurs
     * */
    private JsonNode doGetWithRetry (String url) throws Exception {
        int retryAttempts = 0;
        Exception lastError = null;

        while (retryAttempts++ < 10 && serviceRunning) {
            try {
                return doGet(url, JsonNode.class);
            } catch (CommandFailureException e) {
                lastError = e;
                if (e.getStatusCode() != 429) {
                    // Might be 401, 403 or any other error code here so the code will just get stuck
                    // cycling this failed request until it's fixed. So we need to skip this scenario.
                    logger.error(String.format("ZoomRooms API error %s while retrieving %s data", e.getStatusCode(), url), e);
                    break;
                }
            } catch (Exception e) {
                lastError = e;
                // if service is running, log error
                if (serviceRunning) {
                    logger.error(String.format("ZoomRooms API error while retrieving %s data", url), e);
                }
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }

        if (retryAttempts == 10 && serviceRunning) {
            // if we got here, all 10 attempts failed
            logger.error(String.format("Failed to retrieve %s data", url), lastError);
        }
        return null;
    }

    /**
     * Populate ZoomRooms with properties: metrics, devices, controls etc.
     *
     * @param roomId Id of zoom room
     * @throws Exception if any error occurs
     */
    private void populateDeviceDetails(String roomId) throws Exception {
        AggregatedDevice aggregatedZoomRoomDevice = aggregatedDevices.get(roomId);

        if (aggregatedZoomRoomDevice == null) {
            return;
        }
        // To restore properties that were here before, but to override the rest
        Map<String, String> properties = new HashMap<>(aggregatedZoomRoomDevice.getProperties());

        properties.put(METRICS_ROOM_STATUS, aggregatedZoomRoomDevice.getProperties().get(METRICS_ROOM_STATUS));

        Map<String, String> roomMetricsProperties = zoomRoomsMetricsData.get(roomId);

        if (roomMetricsProperties != null) {
            cleanupStaleProperties(properties, ROOM_STATUS_GROUP);

            Map<String, String> processedMetricsProperties = new HashMap<>();
            Map<String, String> roomIssues = RoomStatusProcessor.processIssuesList(roomMetricsProperties.get(METRICS_ISSUES));
            roomIssues.forEach((key, value) -> processedMetricsProperties.put(ROOM_STATUS_GROUP + key, value));

            properties.putAll(processedMetricsProperties);
            properties.putAll(roomMetricsProperties);
            properties.remove(METRICS_ISSUES);
        }

        populateRoomUserDetails(aggregatedZoomRoomDevice.getSerialNumber(), properties);

        List<AdvancedControllableProperty> controllableProperties = aggregatedZoomRoomDevice.getControllableProperties();

        populateRoomSettings(roomId, properties, controllableProperties);
        retrieveGroupedRoomDevicesInformation(roomId, properties);
        createRoomControls(properties, controllableProperties);

        aggregatedZoomRoomDevice.setProperties(properties);
        aggregatedZoomRoomDevice.setControllableProperties(controllableProperties);
        aggregatedZoomRoomDevice.setTimestamp(System.currentTimeMillis());
    }

    /**
     * Add room user settings to device's properties. Check if retrieval is relevant based on {@link #validUserDetailsDataRetrievalPeriodTimestamps}
     * value, stored with the {@param roomUserId}.
     *
     * @param roomUserId id of the room user to populate properties for for
     * @param properties map to add statistics to
     * */
    private void populateRoomUserDetails(String roomUserId, Map<String, String> properties) throws Exception {
        Long dataRetrievalTimestamp = validUserDetailsDataRetrievalPeriodTimestamps.get(roomUserId);
        long roomUserDetailsProperties = properties.keySet().stream().filter(s -> s.startsWith(ROOM_USER_DETAILS_GROUP)).count();
        if (roomUserDetailsProperties > 0 && dataRetrievalTimestamp != null &&
                dataRetrievalTimestamp > System.currentTimeMillis()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Room User details retrieval is in cooldown. %s seconds left",
                        (dataRetrievalTimestamp - System.currentTimeMillis()) / 1000));
            }
            return;
        }
        validUserDetailsDataRetrievalPeriodTimestamps.put(roomUserId, System.currentTimeMillis() + Math.max(defaultUserDetailsTimeout, roomUserDetailsRetrievalTimeout));

        JsonNode roomUserDetails = retrieveUserDetails(roomUserId);
        Map<String, String> roomUserProperties = new HashMap<>();

        cleanupStaleProperties(properties, ROOM_USER_DETAILS_GROUP);

        if (roomUserDetails != null) {
            aggregatedDeviceProcessor.applyProperties(roomUserProperties, roomUserDetails, "RoomUserDetails");
            properties.putAll(roomUserProperties);
        }
    }

    /**
     * Add room settings controls to device's properties. Check if retrieval is relevant based on {@link #validRoomDevicesDataRetrievalPeriodTimestamps}
     * value, stored with the {@param roomId}.
     *
     * @param roomId id of the room to populate settings for
     * @param properties map to add statistics to
     * @param controllableProperties list of controllable properties, to add controls to
     * */
    private void populateRoomSettings(String roomId, Map<String, String> properties, List<AdvancedControllableProperty> controllableProperties) throws Exception {
        Long dataRetrievalTimestamp = validRoomSettingsDataRetrievalPeriodTimestamps.get(roomId);
        long roomSettingsProperties = properties.keySet().stream().filter(s -> s.startsWith(ROOM_CONTROLS_ALERT_SETTINGS_GROUP) || s.startsWith(ROOM_CONTROLS_MEETING_SETTINGS_GROUP)).count();
        if ((roomSettingsProperties > 0 && dataRetrievalTimestamp != null && dataRetrievalTimestamp > System.currentTimeMillis())) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Room settings retrieval is in cooldown. %s seconds left",
                        (dataRetrievalTimestamp - System.currentTimeMillis()) / 1000));
            }
            return;
        }
        if(!displayRoomSettings) {
            if (logger.isDebugEnabled()) {
                logger.debug("Room settings retrieval is switched off by displayRoomSettings property.");
            }
            return;
        }
        validRoomSettingsDataRetrievalPeriodTimestamps.put(roomId, System.currentTimeMillis() + Math.max(defaultRoomSettingsTimeout, roomSettingsRetrievalTimeout));

        cleanupStaleProperties(properties, ROOM_CONTROLS_ALERT_SETTINGS_GROUP, ROOM_CONTROLS_MEETING_SETTINGS_GROUP);
        cleanupStaleControls(controllableProperties, ROOM_CONTROLS_ALERT_SETTINGS_GROUP, ROOM_CONTROLS_MEETING_SETTINGS_GROUP);

        Map<String, String> settingsProperties = new HashMap<>();
        List<AdvancedControllableProperty> settingsControls = new ArrayList<>();

        JsonNode meetingSettings = retrieveRoomSettings(roomId, "meeting");
        aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, meetingSettings, "RoomMeetingSettings");
        JsonNode alertSettings = retrieveRoomSettings(roomId, "alert");
        aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, alertSettings, "RoomAlertSettings");

        /** TODO: this segment will be removed and moved completely to yml config after SYAL-625 is fixed */
        if (alertSettings != null) {
            JsonNode notificationSettings = alertSettings.get("notification");
            if (notificationSettings != null) {
                JsonNode batteryPercentageValue = notificationSettings.get("battery_percentage");
                if (batteryPercentageValue != null) {
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

        // if the property isn't there - we should not display this control and its label
        settingsControls.removeIf(advancedControllableProperty -> {
            String value = String.valueOf(advancedControllableProperty.getValue());
            if (StringUtils.isNullOrEmpty(value)) {
                settingsProperties.remove(advancedControllableProperty.getName());
                return true;
            }
            return false;
        });

        properties.putAll(settingsProperties);
        controllableProperties.addAll(settingsControls);
    }

    /**
         * Types of devices: ZoomRoomsComputer, Controller, SchedulingDisplay, ZoomRoomsControlSystem, CompanionWhiteboard
     * Retrieve registered zoom room devices information, group it by type.
     * Calculate number of online/offline devices, display online/offline devices operating systems and app versions.
     *
     * @param roomId to get devices for
     * @param properties to save properties to
     * @throws Exception if any error occurs
     * */
    private void retrieveGroupedRoomDevicesInformation(String roomId, Map<String, String> properties) throws Exception {
        Long dataRetrievalTimestamp = validRoomDevicesDataRetrievalPeriodTimestamps.get(roomId);
        long roomDevicesProperties = properties.keySet().stream().filter(s -> s.startsWith("RoomDevices_")).count();
        if (roomDevicesProperties > 0 && dataRetrievalTimestamp != null && dataRetrievalTimestamp > System.currentTimeMillis()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Room devices retrieval is in cooldown. %s seconds left",
                        (dataRetrievalTimestamp - System.currentTimeMillis()) / 1000));
            }
            return;
        }
        validRoomDevicesDataRetrievalPeriodTimestamps.put(roomId, System.currentTimeMillis() + Math.max(defaultRoomDevicesTimeout, roomDevicesRetrievalTimeout));

        JsonNode devices = retrieveRoomDevices(roomId);
        Map<String, List<Map<String, String>>> deviceGroups = new HashMap<>();

        if (devices != null && devices.isArray()) {
            for (JsonNode deviceNode : devices) {
                Map<String, String> roomDeviceProperties = new HashMap<>();
                aggregatedDeviceProcessor.applyProperties(roomDeviceProperties, deviceNode, "RoomDevice");

                String deviceType = roomDeviceProperties.get(DEVICE_TYPE_PROPERTY);
                if (!deviceGroups.containsKey(deviceType)) {
                    deviceGroups.put(deviceType, new ArrayList<>());
                }
                deviceGroups.get(deviceType).add(roomDeviceProperties);
            }

            cleanupStaleProperties(properties, "RoomDevices_");
            // Process device groups
            // Key is group, value is list of mapped properties
            deviceGroups.forEach((key, value) -> {
                List<String> onlineAppVersions = new ArrayList<>();
                List<String> offlineAppVersions = new ArrayList<>();
                List<String> onlineDeviceSystems = new ArrayList<>();
                List<String> offlineDeviceSystems = new ArrayList<>();
                int onlineDevicesTotal = 0;
                int offlineDevicesTotal = 0;
                for (Map<String, String> props: value) {
                    String appVersion = props.get(APP_VERSION_PROPERTY);
                    String deviceSystem = props.get(DEVICE_SYSTEM_PROPERTY);
                    if (Objects.equals("Online", props.get(DEVICE_STATUS_PROPERTY))) {
                        if (!StringUtils.isNullOrEmpty(appVersion)) {
                            onlineAppVersions.add(String.format("%s [%s]", appVersion, deviceSystem));
                        }
                        if (!StringUtils.isNullOrEmpty(deviceSystem)) {
                            onlineDeviceSystems.add(deviceSystem);
                        }
                        onlineDevicesTotal++;
                    } else {
                        if (!StringUtils.isNullOrEmpty(appVersion)) {
                            offlineAppVersions.add(String.format("%s [%s]", appVersion, deviceSystem));
                        }
                        if (!StringUtils.isNullOrEmpty(deviceSystem)) {
                            offlineDeviceSystems.add(deviceSystem);
                        }
                        offlineDevicesTotal++;
                    }
                }
                properties.put(String.format(ROOM_DEVICES_TEMPLATE_PROPERTY, key, ONLINE_APP_VERSIONS_PROPERTY), String.join("; ", onlineAppVersions));
                properties.put(String.format(ROOM_DEVICES_TEMPLATE_PROPERTY, key, OFFLINE_APP_VERSIONS_PROPERTY), String.join("; ", offlineAppVersions));
                properties.put(String.format(ROOM_DEVICES_TEMPLATE_PROPERTY, key, ONLINE_DEVICE_SYSTEMS_PROPERTY), String.join("; ", onlineDeviceSystems));
                properties.put(String.format(ROOM_DEVICES_TEMPLATE_PROPERTY, key, OFFLINE_DEVICE_SYSTEMS_PROPERTY), String.join("; ", offlineDeviceSystems));
                properties.put(String.format(ROOM_DEVICES_TEMPLATE_PROPERTY, key, ONLINE_DEVICES_TOTAL_PROPERTY), String.valueOf(onlineDevicesTotal));
                properties.put(String.format(ROOM_DEVICES_TEMPLATE_PROPERTY, key, OFFLINE_DEVICES_TOTAL_PROPERTY), String.valueOf(offlineDevicesTotal));
            });
        }
    }

    /**
     * Create a list of RoomControls based on room status and save them to properties
     *
     * @param properties map to save statistics values to
     * @param controllableProperties list to save controllable properties to
     */
    private void createRoomControls(Map<String, String> properties, List<AdvancedControllableProperty> controllableProperties) {
        cleanupStaleProperties(properties, ROOM_CONTROLS_GROUP);
        cleanupStaleControls(controllableProperties, ROOM_CONTROLS_GROUP);

        String roomStatus = properties.get(METRICS_ROOM_STATUS);
        if (!StringUtils.isNullOrEmpty(roomStatus) && (roomStatus.equals("InMeeting") || roomStatus.equals("Connecting"))) {
            properties.put(END_CURRENT_MEETING_CONTROL, "");
            controllableProperties.add(createButton(END_CURRENT_MEETING_CONTROL, "End", "Ending...", 0L));

            properties.put(LEAVE_CURRENT_MEETING_CONTROL, "");
            controllableProperties.add(createButton(LEAVE_CURRENT_MEETING_CONTROL, "Leave", "Leaving...", 0L));

            properties.remove(START_ROOM_PMI_CONTROL);
        } else if (!StringUtils.isNullOrEmpty(roomStatus) && !roomStatus.equals("Offline")) {
            properties.put(START_ROOM_PMI_CONTROL, "");
            controllableProperties.add(createButton(START_ROOM_PMI_CONTROL, "Start", "Starting...", 0L));

            properties.remove(END_CURRENT_MEETING_CONTROL);
            properties.remove(LEAVE_CURRENT_MEETING_CONTROL);
        }

        if (!StringUtils.isNullOrEmpty(roomStatus) && !roomStatus.equals("Offline")) {
            properties.put(RESTART_ZOOM_ROOMS_CLIENT_CONTROL, "");
            controllableProperties.add(createButton(RESTART_ZOOM_ROOMS_CLIENT_CONTROL, "Restart", "Restarting...", 0L));
        }
    }

    /**
     * Update value of a cached controllable property to a new value
     *
     * @param roomId       id of the zoomRoom to look up
     * @param propertyName name of the property
     * @param value        new value of the property
     */
    private void updateCachedControllablePropertyValue(String roomId, String propertyName, String value) {
        AggregatedDevice aggregatedDevice = aggregatedDevices.get(roomId);
        List<AdvancedControllableProperty> advancedControllableProperties = aggregatedDevice.getControllableProperties();
        Map<String, String> properties = aggregatedDevice.getProperties();

        advancedControllableProperties.stream().filter(advancedControllableProperty ->
                advancedControllableProperty.getName().equals(propertyName)).findFirst()
                .ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(value));
        properties.put(propertyName, value);

        if (propertyName.startsWith(ROOM_CONTROLS_GROUP)) {
            if (propertyName.endsWith(LEAVE_CURRENT_MEETING_PROPERTY) || propertyName.endsWith(END_CURRENT_MEETING_PROPERTY)) {
                properties.put(METRICS_ROOM_STATUS, "Available");
            } else if (propertyName.endsWith("StartRoomPersonalMeeting")) {
                properties.put(METRICS_ROOM_STATUS, "Connecting");
            }
            createRoomControls(properties, advancedControllableProperties);
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
        JsonNode roomDevices = doGetWithRetry(String.format(ZOOM_DEVICES_URL, roomId));
        if (roomDevices != null && roomDevices.has("devices")) {
            return roomDevices.get("devices");
        }
        return null;
    }

    /**
     * Retrieve list of all ZoomRooms metrics
     * To have better control over data collection - it is bound to {@link ZoomRoomsAggregatorCommunicator#validMetricsDataRetrievalPeriodTimestamp} variable
     * in order to only fetch this information when {@link ZoomRoomsAggregatorCommunicator#metricsRetrievalTimeout} has exceeded
     *
     * @throws Exception if any error occurs
     */
    private void retrieveZoomRoomMetrics() throws Exception {
        if (zoomRoomsMetricsData.size() > 0 && validMetricsDataRetrievalPeriodTimestamp > System.currentTimeMillis()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Metrics retrieval is in cooldown. %s seconds left",
                        (validMetricsDataRetrievalPeriodTimestamp - System.currentTimeMillis()) / 1000));
            }
            return;
        }
        validMetricsDataRetrievalPeriodTimestamp = System.currentTimeMillis() + Math.max(defaultMetricsTimeout, metricsRetrievalTimeout);
        try {
            JsonNode roomsMetrics = doGet(ZOOM_ROOMS_METRICS, JsonNode.class);
            if (roomsMetrics != null && !roomsMetrics.isNull() && roomsMetrics.has("zoom_rooms")) {
                for(JsonNode metric: roomsMetrics.get("zoom_rooms")) {
                    Map<String, String> metricsData = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(metricsData, metric, "ZoomRoomMetrics");
                    zoomRoomsMetricsData.put(metric.get("id").asText(), metricsData);
                }
            }
        } catch (CommandFailureException ex) {
            if (ex.getStatusCode() == 429) {
                logger.warn(String.format("Maximum daily rate limit for %s API was reached.", ZOOM_ROOMS_METRICS), ex);
            }
            throw ex;
        }
    }

    /**
     * Retrieve list of ZoomRooms locations
     *
     * @return response JsonNode
     * @throws Exception if any error occurs
     */
    private JsonNode retrieveZoomRoomLocations() throws Exception {
        JsonNode roomsMetrics = doGetWithRetry(ZOOM_ROOM_LOCATIONS);
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
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveRoomSettings(String roomId, String type) throws Exception {
        return doGetWithRetry(String.format(ZOOM_ROOM_SETTINGS_URL, roomId) + "?setting_type=" + type);
    }

    /**
     * Retrieve settings for an account
     *
     * @param type of settings list to get
     * @return response JsonNode
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveAccountSettings(String type) throws Exception {
        return doGetWithRetry(String.format(ZOOM_ROOM_ACCOUNT_SETTINGS_URL) + "?setting_type=" + type);
    }

    /**
     * Update Zoom Account setting
     *
     * @param setting    name of the setting to update
     * @param value      new value for the property
     * @param type       of the setting, either alert or meeting
     * @param parentNode json parent node to use while building json request payload
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
     * @param roomId     id of the room to update property for
     * @param setting    name of the setting to update
     * @param value      new value for the setting
     * @param type       of the setting, either alert or meeting
     * @param parentNode json parent node to use while building json request payload
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
    private String retrieveIdByRoomId(String roomId) {
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
     *
     * @param userId id of the room user to restart
     * @throws Exception if any error occurs
     */
    private void restartZoomRoomClient(String userId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC, userId), buildRpcRequest("restart"));
    }

    /**
     * Remove an entry from the specified map, if key starts with one of the options provided
     *
     * @param properties map to remove property from
     * @param propertyNames options to use when defining which properties to remove
     */
    private void cleanupStaleProperties(Map<String, String> properties, String... propertyNames) {
        properties.keySet().removeIf(s -> {
            for (String propertyName: propertyNames) {
                return s.startsWith(propertyName);
            }
            return false;
        });
    }

    /**
     * Remove an entry from the specified list of controllable ptoperties, if property name starts with one of the options provided
     *
     * @param advancedControllableProperties list to remove object from
     * @param controlNames options to use when defining which properties to remove
     */
    private void cleanupStaleControls(List<AdvancedControllableProperty> advancedControllableProperties, String... controlNames) {
        advancedControllableProperties.removeIf(advancedControllableProperty -> {
            for (String controlName: controlNames) {
                return advancedControllableProperty.getName().startsWith(controlName);
            }
            return false;
        });
    }

    /**
     * End current Zoom meeting by sending jsonRpc command
     *
     * @param userId id of the room user to make end current meeting for
     * @throws Exception if any error occurs
     */
    private void endCurrentMeeting(String userId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC_MEETINGS, userId), buildRpcRequest("end"));
    }

    /**
     * Leave current Zoom meeting by sending jsonRpc command
     *
     * @param userId id of the room user to make leave the meeting
     * @throws Exception if any error occurs
     */
    private void leaveCurrentMeeting(String userId) throws Exception {
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC_MEETINGS, userId), buildRpcRequest("leave"));
    }

    /**
     * Join room user's Personal meeting room
     *
     * @param userId id of the room user to make leave the meeting
     * @throws Exception if any error occurs
     */
    private void joinRoomPMI(String userId) throws Exception {
        Map<String, Object> request = buildRpcRequest("join");

        Map<String, String> params = new HashMap<>();
        aggregatedDevices.values().stream().filter(aggregatedDevice ->
                aggregatedDevice.getSerialNumber().equals(userId)).findFirst().ifPresent(aggregatedDevice -> {
            Map<String, String> properties = aggregatedDevice.getProperties();
            if (properties != null) {
                params.put("meeting_number", properties.get(ROOM_USER_DETAILS_PMI));
            }
        });
        if (params.isEmpty()) {
            throw new IllegalArgumentException("Unable to start Personal Meeting for user " + userId);
        }

        request.put("params", params);
        doPost(String.format(ZOOM_ROOM_CLIENT_RPC_MEETINGS, userId), request);
    }

    /**
     * Normalize value of a setting control to represent real values - true or false.
     *
     * @param value raw object coming from Symphony
     * @return {@link String} value of 'true' or 'false' based on the initial value
     * */
    private String normalizeSettingData(Object value) {
        return "0".equals(String.valueOf(value)) ? "false" : "true";
    }

}
