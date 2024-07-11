/*
 * Copyright (c) 2021-2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
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
import com.avispl.symphony.dal.communicator.aggregator.data.DeviceStatus;
import com.avispl.symphony.dal.communicator.aggregator.settings.Setting;
import com.avispl.symphony.dal.communicator.aggregator.settings.ZoomRoomsSetting;
import com.avispl.symphony.dal.communicator.aggregator.status.RoomStatusProcessor;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
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
     * Paginated response interface, needed to delegate paginated meetings to a caller
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     * */
    private interface PaginatedResponseProcessor {
        void process(JsonNode response);
    }
    /**
     * Process that is running constantly and triggers collecting data from Zoom API endpoints, based on the given timeouts and thresholds.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class ZoomRoomsDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public ZoomRoomsDeviceDataLoader() {
            logDebugMessage("Creating new device data loader.");
            inProgress = true;
        }

        @Override
        public void run() {
            logDebugMessage("Entering device data loader active stage.");
            mainloop:
            while (inProgress) {
                long startCycle = System.currentTimeMillis();
                try {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e) {
                        // Ignore for now
                    }

                    if (!inProgress) {
                        logDebugMessage("Main data collection thread is not in progress, breaking.");
                        break mainloop;
                    }

                    updateAggregatorStatus();
                    // next line will determine whether Zoom monitoring was paused
                    if (devicePaused) {
                        logDebugMessage("The device communicator is paused, data collector is not active.");
                        continue mainloop;
                    }
                    try {
                        logDebugMessage("Fetching devices list.");
                        fetchDevicesList();
                    } catch (Exception e) {
                        knownErrors.put(ROOMS_LIST_RETRIEVAL_ERROR_KEY, limitErrorMessageByLength(e.getMessage(), maxErrorLength));
                        logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage(), e);
                    }

                    if (!inProgress) {
                        logDebugMessage("The data collection thread is not in progress. Breaking the loop.");
                        break mainloop;
                    }

                    int aggregatedDevicesCount = aggregatedDevices.size();
                    if (aggregatedDevicesCount == 0) {
                        logDebugMessage("No devices collected in the main data collection thread so far. Continuing.");
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
                        knownErrors.put(ROOMS_METRICS_RETRIEVAL_ERROR_KEY, limitErrorMessageByLength(e.getMessage(), maxErrorLength));
                        logger.error("Error occurred during ZoomRooms metrics retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage());
                    }

                    for (AggregatedDevice aggregatedDevice : aggregatedDevices.values()) {
                        if (!inProgress) {
                            logDebugMessage("The data collection thread is not in progress. Breaking the data update loop.");
                            break;
                        }
                        if (executorService == null) {
                            logDebugMessage("Executor service reference is null. Breaking the execution.");
                            break;
                        }
                        devicesExecutionPool.add(executorService.submit(() -> {
                            String deviceId = aggregatedDevice.getDeviceId();
                            try {
                                // We need to only work with rooms here
                                if (!deviceId.startsWith(ROOM_DEVICE_ID_PREFIX)) {
                                    populateDeviceDetails(deviceId);
                                }
                                knownErrors.remove(deviceId);
                            } catch (Exception e) {
                                knownErrors.put(deviceId, limitErrorMessageByLength(e.getMessage(), maxErrorLength));
                                logger.error(String.format("Exception during Zoom Room '%s' data processing.", aggregatedDevice.getDeviceName()), e);
                            }
                        }));
                    }
                    do {
                        try {
                            TimeUnit.MILLISECONDS.sleep(500);
                        } catch (InterruptedException e) {
                            logger.error("Interrupted exception during main loop execution", e);
                            if (!inProgress) {
                                logDebugMessage("Breaking after the main loop execution");
                                break;
                            }
                        }
                        devicesExecutionPool.removeIf(Future::isDone);
                    } while (!devicesExecutionPool.isEmpty());

                    // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                    // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                    // launches devices detailed statistics collection
                    nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

                    lastMonitoringCycleDuration = (System.currentTimeMillis() - startCycle)/1000;
                    logDebugMessage("Finished collecting devices statistics cycle at " + new Date() + ", total duration: " + lastMonitoringCycleDuration);
                } catch(Exception e) {
                    logger.error("Unexpected error occurred during main device collection cycle", e);
                }
            }
            logDebugMessage("Main device collection loop is completed, in progress marker: " + inProgress);
            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            logDebugMessage("Main device details collection loop is stopped!");
            inProgress = false;
        }

        /**
         * Retrieves {@link #inProgress}
         *
         * @return value of {@link #inProgress}
         */
        public boolean isInProgress() {
            return inProgress;
        }
    }

    /**
     * Interceptor for RestTemplate that checks for the response headers populated for certain endpoints
     * such as metrics, to control the amount of requests left per day.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class ZoomRoomsHeaderInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
            ClientHttpResponse response = null;
            try {
                response = execution.execute(request, body);
                String path = request.getURI().getPath();
                if (path.contains("metrics")) {
                    logDebugMessage("Adressing metrics endpoint " + path);
                    List<String> headerData = response.getHeaders().get(RATE_LIMIT_REMAINING_HEADER);
                    if (headerData != null && !headerData.isEmpty()) {
                        metricsRateLimitRemaining = Integer.parseInt(headerData.get(0));
                    }
                }
                if (!path.contains(ZOOM_ROOM_OAUTH_URL) && (response.getStatusCode().equals(HttpStatus.UNAUTHORIZED)
                        || System.currentTimeMillis() >= oauthTokenExpiresIn + oauthTokenGeneratedTimestamp || oauthTokenGeneratedTimestamp == 0L)) {
                    try {
                        authenticate();
                        HttpHeaders headers = request.getHeaders();
                        headers.put("Authorization", Collections.singletonList("Bearer " + oAuthAccessToken));
                        response = execution.execute(request, body);
                    } catch (Exception e) {
                        logger.error("Unable to log in using OAuth.", e);
                    }
                }
                return response;
            } catch (Exception e) {
                //knownErrors.put(LOGIN_ERROR_KEY, e.getMessage());
                logger.error("An exception occurred during request execution", e);
            }
            return response;
    }
        }

    private static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String BASE_ZOOM_URL = "v2";
    private static final String ZOOM_ROOMS_URL = "rooms?page_size=%s";
    private static final String ZOOM_DEVICES_URL = "rooms/%s/devices"; // Requires room Id
    private static final String ZOOM_ROOM_SETTINGS_URL = "/rooms/%s/settings"; // Requires room Id
    private static final String ZOOM_ROOM_ACCOUNT_SETTINGS_URL = "rooms/account_settings";
    private static final String ZOOM_ROOMS_METRICS_URL = "metrics/zoomrooms?page_size=%s";
    private static final String ZOOM_ROOM_LOCATIONS_URL = "rooms/locations?page_size=%s";
    private static final String ZOOM_USER_DETAILS_URL = "users/%s"; // Requires room user id
    private static final String ZOOM_ROOM_METRICS_DETAILS_URL = "metrics/zoomrooms/%s"; // Required room id
    private static final String ZOOM_ROOM_DEVICES_URL = "rooms/%s/devices";

    /**
     * Room device id prefix, to separate room devices from rooms in {@link #aggregatedDevices} map
     * @since 1.2.0
     * */
    private static final String ROOM_DEVICE_ID_PREFIX = "room_device:";

    /**
     * URL template for OAuth authentication
     * @since 1.1.0
     * */
    private static final String ZOOM_ROOM_OAUTH_PARAMS_URL = "?grant_type=account_credentials&account_id=%s";

    /**
     * URL template for OAuth authentication
     * @since 1.1.0
     * */
    private static final String ZOOM_ROOM_OAUTH_URL = "oauth/token";

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;

    /**
     * API Token (OAuth) used for authorization in Zoom API
     */
    private volatile String oAuthAccessToken;

    /**
     * OAuth token expiration timestamp
     * @since 1.1.0
     * */
    private volatile long oauthTokenExpiresIn;

    /**
     * Timestamp of the latest OAuth token generation
     * @since 1.1.0
     * */
    private volatile long oauthTokenGeneratedTimestamp;

    /**
     *
     * */
    private ReentrantLock dataCollectorOperationsLock = new ReentrantLock();

    /**
     * List of the latest errors (not critical that do not cancel out general devices processing mechanism)
     * @since 1.1.0
     * */
    private ConcurrentHashMap<String, String> knownErrors = new ConcurrentHashMap<>();

    /**
     * Devices this aggregator is responsible for
     * Data is cached and retrieved every {@link #defaultMetaDataTimeout}
     */
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();

    /**
     * Cached metrics data, retrieved from the resource-heavy API. Since daily request rate is limited - it must be cached
     * and retrieved from the cache. Data is retrieved every {@link #metricsRetrievalTimeout}
     */
    private ConcurrentHashMap<String, Map<String, String>> zoomRoomsMetricsData = new ConcurrentHashMap<>();

    /**
     * Interceptor for RestTemplate that injects
     * authorization header and fixes malformed headers sent by XIO backend
     */
    private ClientHttpRequestInterceptor zoomRoomsHeaderInterceptor = new ZoomRoomsHeaderInterceptor();

    /**
     * Adapter metadata, collected from the version.properties
     */
    private Properties adapterProperties;

    /**
     * How much time last monitoring cycle took to finish
     * */
    private Long lastMonitoringCycleDuration;

    /**
     * Locations specified for filtering
     */
    private String zoomRoomLocations;

    /**
     * Property groups to exclude.
     * RoomUserDetails | RoomControlSettings | RoomDevices
     *
     * */
    private List<String> excludePropertyGroups = new ArrayList<>();

    /**
     * Zoom Room types specified for filtering
     */
    private String zoomRoomTypes; // ZoomRoom, SchedulingDisplayOnly, DigitalSignageOnly

    /**
     * Include Zoom Room Devices as separate devices
     * */
    private boolean includeRoomDevices = false;

    /**
     * Include rooms metrics into the dataset
     * */
    private boolean includeRoomsMetrics = true;

    /**
     * Include room devices endpoint statistics
     * */
    private boolean includeRoomDevicesInCalls = false;

    /**
     * Configurable zoom OAuth hostname, zoom.us by default
     * @since 1.1.0
     * */
    private String zoomOAuthHostname = "zoom.us";

    /**
     * Maximum error length to display in the Errors group on aggregator level
     * @since 1.1.0
     * */
    private int maxErrorLength = 120;

    /**
     * Account id to authorize in, when OAuth authentication type is used
     * @since 1.1.0
     * */
    private String accountId;

    /**
     * Remaining daily call rate limit for metrics endpoint.
     * It is of type {@link Integer} to avoid comparing to 0 and including 0 as a value of extended properties
     */
    private volatile Integer metricsRateLimitRemaining;

    /**
     * Whether service is running.
     */
    private volatile boolean serviceRunning;

    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;

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
     * Default limit for {@link #liveMeetingDetailsDailyRequestRateThreshold}
     */
    private static final int defaultMeetingDetailsDailyRequestRateThreshold = 5000;

    /**
     * Aggregator inactivity timeout. If the {@link ZoomRoomsAggregatorCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 180000;

    /**
     * Device metadata retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceMetaDataRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Device metrics retrieval timeout. Device metrics are retrieved once during this time period.
     */
    private long metricsRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Active conference details retrieval timeout. Active conference details are retrieved once during this time period.
     * @since 1.0.1
     */
    private long liveMeetingDetailsRetrievalTimeout = 60 * 1000 / 2;

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
     * Size of room responses, in pages.
     */
    private int roomRequestPageSize = 300;

    /**
     * Size of room responses, in pages.
     */
    private int locationRequestPageSize = 300;

    /**
     * Size of room metrics responses, in pages.
     */
    private int roomMetricsPageSize = 300;

    /**
     * The bottom rate limit for meeting details retrieval for rooms that have status InMeeting.
     * If {@link #metricsRateLimitRemaining} is less than this value - metrics details are not populated
     * (except for the general metrics data)
     */
    private int liveMeetingDetailsDailyRequestRateThreshold = 5000;

    /**
     * Number of threads assigned for the data collection jobs
     * */
    private int executorServiceThreadCount = 8;
    /**
     * Whether or not to show the LiveMeeting details for the rooms that have status InMeeting
     */
    private boolean displayLiveMeetingDetails = false;

    /**
     * Triggers visibility of Room property groups:
     * RoomControlsAlertSettings, RoomControlsMeetingSettings
     */
    private boolean displayRoomSettings;

    /**
     * Triggers visibility of Aggregator property groups:
     * AccountAlertSettings, AccountMeetingSettings
     */
    private boolean displayAccountSettings;

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
     * Time period within which the device meetings metrics (dynamic information) cannot be refreshed (per room).
     * Ignored if metrics data is not yet retrieved
     */
    private ConcurrentHashMap<String, Long> validLiveMeetingsDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Map of roomUserId:timestamp within which the room user details cannot be refreshed.
     * Ignored if the data is not yet retrieved for the room
     */
    private ConcurrentHashMap<String, Long> validUserDetailsDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Map of roomId:timestamp within which the room settings cannot be refreshed.
     * Ignored if the data is not yet retrieved for the room
     */
    private ConcurrentHashMap<String, Long> validRoomSettingsDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * Map of roomId:timestamp within which the registered room devices details cannot be refreshed.
     * Ignored if the data is not yet retrieved for the room
     */
    private ConcurrentHashMap<String, Long> validRoomDevicesDataRetrievalPeriodTimestamps = new ConcurrentHashMap<>();

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private volatile long nextDevicesCollectionIterationTimestamp;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link ZoomRoomsAggregatorCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    /**
     * Executor that runs all the async operations, that {@link #deviceDataLoader} is posting and
     * {@link #devicesExecutionPool} is keeping track of
     */
    private ExecutorService executorService;

    /**
     * Runner service responsible for collecting data and posting processes to {@link #devicesExecutionPool}
     */
    private ZoomRoomsDeviceDataLoader deviceDataLoader;

    /**
     * Format date for utility purposes
     * @since 1.0.1
     */
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss'Z'");

    /**
     * Pool for keeping all the async operations in, to track any operations in progress and cancel them if needed
     */
    private List<Future> devicesExecutionPool = new ArrayList<>();

    /**
     * Retrieves {@link #includeRoomsMetrics}
     *
     * @return value of {@link #includeRoomsMetrics}
     */
    public boolean isIncludeRoomsMetrics() {
        return includeRoomsMetrics;
    }

    /**
     * Sets {@link #includeRoomsMetrics} value
     *
     * @param includeRoomsMetrics new value of {@link #includeRoomsMetrics}
     */
    public void setIncludeRoomsMetrics(boolean includeRoomsMetrics) {
        this.includeRoomsMetrics = includeRoomsMetrics;
    }

    /**
     * Retrieves {@link #includeRoomDevicesInCalls}
     *
     * @return value of {@link #includeRoomDevicesInCalls}
     */
    public boolean isIncludeRoomDevicesInCalls() {
        return includeRoomDevicesInCalls;
    }

    /**
     * Sets {@link #includeRoomDevicesInCalls} value
     *
     * @param includeRoomDevicesInCalls new value of {@link #includeRoomDevicesInCalls}
     */
    public void setIncludeRoomDevicesInCalls(boolean includeRoomDevicesInCalls) {
        this.includeRoomDevicesInCalls = includeRoomDevicesInCalls;
    }

    /**
     * Retrieves {@link #executorServiceThreadCount}
     *
     * @return value of {@link #executorServiceThreadCount}
     */
    public int getExecutorServiceThreadCount() {
        return executorServiceThreadCount;
    }

    /**
     * Sets {@link #executorServiceThreadCount} value
     *
     * @param executorServiceThreadCount new value of {@link #executorServiceThreadCount}
     */
    public void setExecutorServiceThreadCount(int executorServiceThreadCount) {
        if (executorServiceThreadCount == 0) {
            this.executorServiceThreadCount = 8;
        } else {
            this.executorServiceThreadCount = executorServiceThreadCount;
        }
    }

    /**
     * Retrieves {@link #excludePropertyGroups}
     *
     * @return value of {@link #excludePropertyGroups}
     */
    public String getExcludePropertyGroups() {
        return String.join(",", excludePropertyGroups);
    }

    /**
     * Sets {@link #excludePropertyGroups} value
     *
     * @param excludePropertyGroups new value of {@link #excludePropertyGroups}
     */
    public void setExcludePropertyGroups(String excludePropertyGroups) {
        this.excludePropertyGroups = Arrays.stream(excludePropertyGroups.split(",")).map(String::trim).collect(Collectors.toList());
    }

    /**
     * Retrieves {@link #includeRoomDevices}
     *
     * @return value of {@link #includeRoomDevices}
     */
    public boolean isIncludeRoomDevices() {
        return includeRoomDevices;
    }

    /**
     * Sets {@link #includeRoomDevices} value
     *
     * @param includeRoomDevices new value of {@link #includeRoomDevices}
     */
    public void setIncludeRoomDevices(boolean includeRoomDevices) {
        this.includeRoomDevices = includeRoomDevices;
    }

    /**
     * Retrieves {@link #maxErrorLength}
     *
     * @return value of {@link #maxErrorLength}
     */
    public int getMaxErrorLength() {
        return maxErrorLength;
    }

    /**
     * Sets {@link #maxErrorLength} value
     *
     * @param maxErrorLength new value of {@link #maxErrorLength}
     */
    public void setMaxErrorLength(int maxErrorLength) {
        this.maxErrorLength = maxErrorLength;
    }

    /**
     * Retrieves {@link #zoomOAuthHostname}
     *
     * @return value of {@link #zoomOAuthHostname}
     */
    public String getZoomOAuthHostname() {
        return zoomOAuthHostname;
    }

    /**
     * Sets {@link #zoomOAuthHostname} value
     *
     * @param zoomOAuthHostname new value of {@link #zoomOAuthHostname}
     */
    public void setZoomOAuthHostname(String zoomOAuthHostname) {
        this.zoomOAuthHostname = zoomOAuthHostname;
    }

    /**
     * Retrieves {@link #accountId}
     *
     * @return value of {@link #accountId}
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets {@link #accountId} value
     *
     * @param accountId new value of {@link #accountId}
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Retrieves {@code {@link #liveMeetingDetailsDailyRequestRateThreshold }}
     *
     * @return value of {@link #liveMeetingDetailsDailyRequestRateThreshold}
     */
    public int getLiveMeetingDetailsDailyRequestRateThreshold() {
        return liveMeetingDetailsDailyRequestRateThreshold;
    }

    /**
     * Sets {@code meetingDetailsBottomRateLimit}
     * if the value is less than {@link #defaultMeetingDetailsDailyRequestRateThreshold} - use latter as a value for
     * {@link #liveMeetingDetailsDailyRequestRateThreshold}
     *
     * @param liveMeetingDetailsDailyRequestRateThreshold the {@code int} field
     */
    public void setLiveMeetingDetailsDailyRequestRateThreshold(int liveMeetingDetailsDailyRequestRateThreshold) {
        this.liveMeetingDetailsDailyRequestRateThreshold = Math.max(liveMeetingDetailsDailyRequestRateThreshold, defaultMeetingDetailsDailyRequestRateThreshold);
    }

    /**
     * Retrieves {@code {@link #roomRequestPageSize}}
     *
     * @return value of {@link #roomRequestPageSize}
     */
    public int getRoomRequestPageSize() {
        return roomRequestPageSize;
    }

    /**
     * Sets {@code roomRequestPageSize}
     *
     * @param roomRequestPageSize the {@code int} field
     */
    public void setRoomRequestPageSize(int roomRequestPageSize) {
        this.roomRequestPageSize = roomRequestPageSize;
    }

    /**
     * Retrieves {@link #roomMetricsPageSize}
     *
     * @return value of {@link #roomMetricsPageSize}
     */
    public int getRoomMetricsPageSize() {
        return roomMetricsPageSize;
    }

    /**
     * Sets {@link #roomMetricsPageSize} value
     *
     * @param roomMetricsPageSize new value of {@link #roomMetricsPageSize}
     */
    public void setRoomMetricsPageSize(int roomMetricsPageSize) {
        this.roomMetricsPageSize = roomMetricsPageSize;
    }

    /**
     * Retrieves {@link #locationRequestPageSize}
     *
     * @return value of {@link #locationRequestPageSize}
     */
    public int getLocationRequestPageSize() {
        return locationRequestPageSize;
    }

    /**
     * Sets {@link #locationRequestPageSize} value
     *
     * @param locationRequestPageSize new value of {@link #locationRequestPageSize}
     */
    public void setLocationRequestPageSize(int locationRequestPageSize) {
        this.locationRequestPageSize = locationRequestPageSize;
    }

    /**
     * Retrieves {@code {@link #displayLiveMeetingDetails }}
     *
     * @return value of {@link #displayLiveMeetingDetails}
     */
    public boolean isDisplayLiveMeetingDetails() {
        return displayLiveMeetingDetails;
    }

    /**
     * Sets {@code showLiveMeetingDetails}
     *
     * @param displayLiveMeetingDetails the {@code boolean} field
     */
    public void setDisplayLiveMeetingDetails(boolean displayLiveMeetingDetails) {
        this.displayLiveMeetingDetails = displayLiveMeetingDetails;
    }

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
        this.deviceMetaDataRetrievalTimeout = Math.max(defaultMetaDataTimeout, deviceMetaDataRetrievalTimeout);
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
        this.zoomRoomLocations = zoomRoomLocations.replaceAll(" ", "");
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
        this.metricsRetrievalTimeout = Math.max(defaultMetricsTimeout, metricsRetrievalTimeout);
    }

    /**
     * Retrieves {@code {@link #liveMeetingDetailsRetrievalTimeout }}
     *
     * @return value of {@link #liveMeetingDetailsRetrievalTimeout}
     * @since 1.0.1
     */
    public long getLiveMeetingDetailsRetrievalTimeout() {
        return liveMeetingDetailsRetrievalTimeout;
    }

    /**
     * Sets {@code activeConferenceDetailsRetrievalTimeout}
     *
     * @param liveMeetingDetailsRetrievalTimeout the {@code long} field
     */
    public void setLiveMeetingDetailsRetrievalTimeout(long liveMeetingDetailsRetrievalTimeout) {
        this.liveMeetingDetailsRetrievalTimeout = liveMeetingDetailsRetrievalTimeout;
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
        this.roomUserDetailsRetrievalTimeout = Math.max(defaultUserDetailsTimeout, roomUserDetailsRetrievalTimeout);
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
        this.roomSettingsRetrievalTimeout = Math.max(defaultRoomSettingsTimeout, roomSettingsRetrievalTimeout);
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
        this.roomDevicesRetrievalTimeout = Math.max(defaultRoomDevicesTimeout, roomDevicesRetrievalTimeout);
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

        executorService = Executors.newFixedThreadPool(executorServiceThreadCount);
        executorService.submit(deviceDataLoader = new ZoomRoomsDeviceDataLoader());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Additional interceptor to RestTemplate that checks the amount of requests left for metrics endpoints
     */
    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();

        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getInterceptors();
        if (!interceptors.contains(zoomRoomsHeaderInterceptor))
            interceptors.add(zoomRoomsHeaderInterceptor);

        return restTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * OAuth based authorization
     */
    @Override
    protected void authenticate() throws Exception {
        generateAccessToken();
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
                    if (logger.isWarnEnabled()) {
                        logger.warn(String.format("Controllable Property %s is unsupported", property));
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
        if (knownErrors.containsKey(LOGIN_ERROR_KEY)) {
            throw new FailedLoginException(knownErrors.get(LOGIN_ERROR_KEY));
        }
        Map<String, String> statistics = new HashMap<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        Map<String, String> dynamicStatistics = new HashMap<>();

        List<AdvancedControllableProperty> accountSettingsControls = new ArrayList<>();
        if (displayAccountSettings) {
            JsonNode meetingSettings = retrieveAccountSettings("meeting");
            if (meetingSettings != null) {
                aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings("meeting"), "AccountMeetingSettings");
            }
            JsonNode alertSettings = retrieveAccountSettings("alert");
            if (alertSettings != null) {
                aggregatedDeviceProcessor.applyProperties(statistics, accountSettingsControls, retrieveAccountSettings("alert"), "AccountAlertSettings");
            }
            // if the property isn't there - we should not display this control and its label
            accountSettingsControls.removeIf(advancedControllableProperty -> {
                String value = String.valueOf(advancedControllableProperty.getValue());
                if (StringUtils.isNullOrEmpty(value)) {
                    statistics.remove(advancedControllableProperty.getName());
                    return true;
                }
                return false;
            });
        }

        statistics.put(ADAPTER_VERSION, adapterProperties.getProperty("mock.aggregator.version"));
        statistics.put(ADAPTER_BUILD_DATE, adapterProperties.getProperty("mock.aggregator.build.date"));

        long adapterUptime = System.currentTimeMillis() - adapterInitializationTimestamp;
        statistics.put(ADAPTER_UPTIME_MIN, String.valueOf(adapterUptime / (1000*60)));
        statistics.put(ADAPTER_UPTIME, normalizeUptime(adapterUptime/1000));

        if (lastMonitoringCycleDuration != null) {
            dynamicStatistics.put("LastMonitoringCycleDuration(s)", String.valueOf(lastMonitoringCycleDuration));
        }
        dynamicStatistics.put("MonitoredDevicesTotal", String.valueOf(aggregatedDevices.size()));

        knownErrors.forEach((key, value) -> statistics.put("Errors#" + key, value));
        if (metricsRateLimitRemaining != null) {
            statistics.put("DashboardMetricsDailyRateLimitRemaining", String.valueOf(metricsRateLimitRemaining));
        }

        extendedStatistics.setDynamicStatistics(dynamicStatistics);
        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(accountSettingsControls);
        return Collections.singletonList(extendedStatistics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void internalInit() throws Exception {
        logDebugMessage("Internal init is called.");
        adapterInitializationTimestamp = System.currentTimeMillis();
        setBaseUri(BASE_ZOOM_URL);
        // To synchronize with the format that Zoom API provides
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        long currentTimestamp = System.currentTimeMillis();
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp;
        validMetricsDataRetrievalPeriodTimestamp = currentTimestamp;
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        serviceRunning = true;
        super.internalInit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void internalDestroy() {
        logDebugMessage("Internal destroy is called.");
        try {
            serviceRunning = false;
            oAuthAccessToken = null;
            if (deviceDataLoader != null) {
                deviceDataLoader.stop();
                deviceDataLoader = null;
            }

            dataCollectorOperationsLock.lock();
            try {
                if (executorService != null) {
                    executorService.shutdownNow();
                    executorService = null;
                }
            } finally {
                dataCollectorOperationsLock.unlock();
            }
            devicesExecutionPool.forEach(future -> future.cancel(true));
            devicesExecutionPool.clear();

            aggregatedDevices.clear();
            zoomRoomsMetricsData.clear();
            validUserDetailsDataRetrievalPeriodTimestamps.clear();
            validRoomDevicesDataRetrievalPeriodTimestamps.clear();
            validRoomSettingsDataRetrievalPeriodTimestamps.clear();
            validLiveMeetingsDataRetrievalPeriodTimestamps.clear();
        } catch (Exception e) {
            logger.error("Error while adapter internalDestroy operation", e);
        } finally {
            super.internalDestroy();
        }
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
                        logDebugMessage(String.format("PING DISCONNECTED: Connection to %s did not succeed within the timeout period of %sms", this.getHost(), this.getPingTimeout()));
                        return this.getPingTimeout();
                    }
                } catch (SocketTimeoutException tex) {
                    logDebugMessage(String.format("PING TIMEOUT: Connection to %s did not succeed within the timeout period of %sms", this.getHost(), this.getPingTimeout()));
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
        if (uri.contains(ZOOM_ROOM_OAUTH_URL)) {
            String oauthPair = getLogin() + ":" + getPassword(); // ClientId:ClientSecret in OAuth flow
            logDebugMessage("Attempt to generate OAuth token with credentials: " + oauthPair);
            headers.add("Authorization", "Basic " + Base64.getEncoder().encodeToString(oauthPair.getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.add("Authorization", "Bearer " + oAuthAccessToken);
        }
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws FailedLoginException {
        logDebugMessage(String.format("Adapter initialized: %s, executorService exists: %s, serviceRunning: %s, devicesExecutionPool: %s", isInitialized(), executorService != null, serviceRunning, devicesExecutionPool.size()));
        if (knownErrors.containsKey(LOGIN_ERROR_KEY)) {
            throw new FailedLoginException(knownErrors.get(LOGIN_ERROR_KEY));
        }
        updateValidRetrieveStatisticsTimestamp();
        dataCollectorOperationsLock.lock();
        try {
            if (executorService == null) {
                // Due to the bug that after changing properties on fly - the adapter is destroyed but adapter is not initialized properly,
                // so executor service is not running. We need to make sure executorService exists
                executorService = Executors.newFixedThreadPool(8);
                executorService.submit(deviceDataLoader = new ZoomRoomsDeviceDataLoader());
                serviceRunning = true;
            }
        } finally {
            dataCollectorOperationsLock.unlock();
        }
        logDebugMessage(String.format("Aggregator Multiple statistics requested. Aggregated Devices collected so far: %s. Runner thread running: %s. Executor terminated: %s",
                    aggregatedDevices.size(), serviceRunning, executorService.isTerminated()));

        long currentTimestamp = System.currentTimeMillis();
        nextDevicesCollectionIterationTimestamp = currentTimestamp;
        logDebugMessage("Zoom Rooms Collected Devices: " + aggregatedDevices.values());

        for (AggregatedDevice aggregatedDevice: aggregatedDevices.values()) {
            aggregatedDevice.setTimestamp(currentTimestamp);
            logDebugMessage("Updating Zoom Room Devices InCall Status");

            Map<String, String> properties = aggregatedDevice.getProperties();
            boolean callStatus = properties.containsKey(METRICS_ROOM_STATUS) && DeviceStatus.isInCall(properties.get(METRICS_ROOM_STATUS));
            try {
                logDebugMessage(String.format("Updating %s device call status to %s", aggregatedDevice.getDeviceId(), callStatus));
                setRoomInCall(aggregatedDevice, callStatus);

                Boolean deviceOnline = aggregatedDevice.getDeviceOnline();
                if (!deviceOnline) {
                    properties.put(METRICS_DATA_DEVICE_UPTIME, "ZR Offline");
                    properties.put(METRICS_DATA_DEVICE_UPTIME_MIN, "ZR Offline");
                }
            } catch (Exception e) {
                logger.error("An error occured during call status setting for room" + aggregatedDevice.getDeviceId() + callStatus);
            }
        }
        return new ArrayList<>(aggregatedDevices.values());
    }

    /**
     * Retrieve Zoom Rooms devices with basic information and save it to {@link #aggregatedDevices} collection
     * Filter Zoom Rooms based on location id.
     * In order to make it more user-friendly, it is expected that {@link #zoomRoomLocations} will contain
     * csv list of Location Names, e.g "Country/Region1", "State1" etc.
     * <p>
     *
     * @throws Exception if a communication error occurs
     */
    private void fetchDevicesList() throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        if (validDeviceMetaDataRetrievalPeriodTimestamp > currentTimestamp) {
            logDebugMessage(String.format("General devices metadata retrieval is in cooldown. %s seconds left",
                    (validDeviceMetaDataRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            return;
        }
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp + deviceMetaDataRetrievalTimeout;

        List<String> retrievedRoomIds = new ArrayList<>();
        fetchRooms(retrievedRoomIds);
        aggregatedDevices.keySet().removeIf(existingDevice -> !retrievedRoomIds.contains(existingDevice) && !existingDevice.startsWith(ROOM_DEVICE_ID_PREFIX));

        if (includeRoomDevices){
            fetchRoomDevices(retrievedRoomIds);
        }
        // Remove rooms that were not populated by the API
        if (retrievedRoomIds.isEmpty()) {
            // If all the devices were not populated for any specific reason (no devices available, filtering, etc)
            aggregatedDevices.clear();
        }
        aggregatedDevices.keySet().removeIf(deviceId -> !retrievedRoomIds.contains(deviceId));
        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();

        knownErrors.remove(ROOMS_LIST_RETRIEVAL_ERROR_KEY);
        logDebugMessage("Fetched devices list: " + aggregatedDevices);
    }

    /**
     * Retrieve Zoom Room devices and add them to an {@link #aggregatedDevices} list, with "room_device" prefix to the id
     * @param retrievedRoomIds list of retrieved device ids to keep track of
     * @throws Exception if any error occurs
     * */
    private void fetchRooms(List<String> retrievedRoomIds) throws Exception {
        List<String> supportedLocationIds = new ArrayList<>();
        boolean zoomRoomLocationsProvided = !StringUtils.isNullOrEmpty(zoomRoomLocations);
        if (zoomRoomLocationsProvided) {
            processPaginatedResponse(ZOOM_ROOM_LOCATIONS_URL, locationRequestPageSize, (roomLocations) -> {
                if (!roomLocations.isNull() && roomLocations.has("locations")) {
                    roomLocations = roomLocations.get("locations");

                    if (roomLocations != null && roomLocations.isArray()) {
                        for (JsonNode roomLocation : roomLocations) {
                            Map<String, String> location = new HashMap<>();
                            aggregatedDeviceProcessor.applyProperties(location, roomLocation, "RoomLocation");
                            if (zoomRoomLocations.contains(location.get(LOCATION_NAME))) {
                                supportedLocationIds.add(location.get(LOCATION_ID));
                            }
                        }
                    }
                }
            });

            logDebugMessage("Updated fetched locations. Supported locationIds: " + supportedLocationIds);
        } else {
            logDebugMessage("Locations filter is not provided, skipping room filtering by location.");
        }

        List<AggregatedDevice> zoomRooms = new ArrayList<>();
        if (zoomRoomLocationsProvided) {
            // if locations are provided, but not retrieved successfully
            // (like if there was a typo or location that does not exist) - skip this step
            if (!supportedLocationIds.isEmpty()) {
                supportedLocationIds.forEach(locationId -> {
                    try {
                        processPaginatedZoomRoomsRetrieval(locationId, zoomRooms);
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to retrieve Zoom Room entries by given locationId: " + locationId, e);
                    }
                });
            }
        } else {
            processPaginatedZoomRoomsRetrieval(null, zoomRooms);
        }

        zoomRooms.forEach(aggregatedDevice -> {
            String deviceId = aggregatedDevice.getDeviceId();
            retrievedRoomIds.add(deviceId);
            if (aggregatedDevices.containsKey(deviceId)) {
                aggregatedDevices.get(deviceId).setDeviceOnline(aggregatedDevice.getDeviceOnline());
            } else {
                aggregatedDevices.put(deviceId, aggregatedDevice);
            }
        });

        logDebugMessage("Updated ZoomRooms devices metadata: " + aggregatedDevices);
    }

    /**
     * Retrieve Zoom Room devices and add them to an {@link #aggregatedDevices} list, with "room_device" prefix to the id 0
     *
     * @param retrievedRoomIds list to add room ids to
     * */
    private void fetchRoomDevices(List<String> retrievedRoomIds) throws Exception {
        for(String roomId: aggregatedDevices.keySet()) {
            retrievedRoomIds.addAll(updateAndRetrieveRoomDevices(roomId));
        }
    }

    /**
     * Retrieve information of cached room devices and update it
     *
     * @param roomId to update room devices for
     * @return list of collected deviceIds
     * @throws Exception if an error occurs during zoom API communication
     * */
    private List<String> updateAndRetrieveRoomDevices(String roomId) throws Exception {
        List<String> collectedDeviceIds = new ArrayList<>();
        if (roomId.startsWith(ROOM_DEVICE_ID_PREFIX)) {
            return collectedDeviceIds;
        }
        processPaginatedResponse(String.format(ZOOM_ROOM_DEVICES_URL, roomId), roomRequestPageSize, roomDevice -> {
            ArrayNode devices = (ArrayNode) roomDevice.at(DEVICES_PATH);
            if (!devices.isMissingNode() && !devices.isEmpty()) {
                devices.forEach(jsonNode -> {
                    AggregatedDevice device = new AggregatedDevice();
                    Map<String, String> deviceProperties = new HashMap<>();

                    String deviceId = jsonNode.at(ID_PATH).asText();
                    if (StringUtils.isNullOrEmpty(deviceId)){
                        return;
                    }
                    device.setDeviceId(deviceId);

                    String serialNumber = jsonNode.at(SERIAL_NUMBER_PATH).asText();
                    String deviceHostname = jsonNode.at(HOSTNAME_PATH).asText();
                    String roomName = jsonNode.at(ROOM_NAME_PATH).asText();
                    if (StringUtils.isNotNullOrEmpty(deviceHostname)) {
                        device.setDeviceName(String.format(ROOM_DEVICE_NAME_PATTERN, roomName, deviceHostname));
                    } else {
                        device.setDeviceName(String.format(ROOM_DEVICE_NAME_PATTERN, roomName, serialNumber));
                    }

                    device.setSerialNumber(serialNumber);
                    device.setCategory(jsonNode.at(TYPE_PATH).asText());
                    device.setDeviceModel(jsonNode.at(MODEL_PATH).asText());
                    device.setDeviceOnline(DeviceStatus.isOnline(jsonNode.at(STATUS_PATH).asText()));
                    List<String> macAddresses = new ArrayList<>();
                    for(JsonNode macAddress: jsonNode.at(MAC_ADDRESS_PATH)){
                        macAddresses.add(validateAndFormatMACAddress(macAddress.asText()));
                    }
                    device.setMacAddresses(macAddresses);

                    deviceProperties.put(DEVICE_SYSTEM_KEY, jsonNode.at(SYSTEM_PATH).asText());
                    deviceProperties.put(DEVICE_APP_VERSION_KEY, jsonNode.at(APP_VERSION_PATH).asText());
                    deviceProperties.put(DEVICE_FIRMWARE_KEY, jsonNode.at(FIRMWARE_PATH).asText());
                    deviceProperties.put(DEVICE_IP_ADDRESS_KEY, jsonNode.at(IP_ADDRESS_PATH).asText());
                    deviceProperties.put(DEVICE_DEVICE_TYPE_KEY, jsonNode.at(TYPE_PATH).asText());
                    deviceProperties.put("UpdateTime", String.valueOf(new Date()));
                    deviceProperties.put("ZoomRoomId", roomId);
                    device.setProperties(deviceProperties);

                    String roomDeviceId = ROOM_DEVICE_ID_PREFIX + device.getDeviceId();
                    if (includeRoomDevicesInCalls) {
                        AggregatedDevice parentDevice = aggregatedDevices.get(roomId);
                        applyParentEndpointStatisticsToDevice(parentDevice, device);
                    }
                    collectedDeviceIds.add(roomDeviceId);
                    aggregatedDevices.put(roomDeviceId, device);
                });
            }
        });
        return collectedDeviceIds;
    }

    /**
     *
     * */
    private void applyParentEndpointStatisticsToDevice(AggregatedDevice parentDevice, AggregatedDevice childDevice) {
        if (parentDevice != null) {
            List<Statistics> roomStatistics = parentDevice.getMonitoredStatistics();
            if (roomStatistics != null) {
                if (roomStatistics.isEmpty()) {
                    childDevice.setMonitoredStatistics(roomStatistics);
                } else {
                    for (Statistics statistics : roomStatistics) {
                        if (statistics instanceof EndpointStatistics) {
                            childDevice.setMonitoredStatistics(Collections.singletonList(statistics));
                        }
                    }
                }
            }
        }
    }
    /**
     * Format mac addresses of different formats to match a single pattern 00:00:00:00:00:00
     *
     * @param macAddress original macAddress
     * @return formatted mac address
     * */
    private String validateAndFormatMACAddress(String macAddress) {
        macAddress = macAddress.replaceAll("[^a-zA-Z0-9]", "");
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i != 5) {
                res.append(macAddress, i * 2, (i + 1) * 2).append(":");
            } else {
                res.append(macAddress.substring(i * 2));
            }
        }
        return res.toString();
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
     * Retrieve list of ZoomRooms available
     *
     * @param locationId id if a location to filter rooms for. Null or empty if none should be applied
     * @param nextPageToken token to reference the next page. Null or empty if shouldn't be applied
     * @return response pair of JsonNode and next_page_token
     * @throws Exception if a communication error occurs
     */
    private Pair<JsonNode, String> retrieveZoomRooms(String locationId, String nextPageToken) throws Exception {
        StringBuilder queryString = new StringBuilder();
        if (!StringUtils.isNullOrEmpty(zoomRoomTypes)) {
            queryString.append("&type=").append(zoomRoomTypes);
        }
        if (!StringUtils.isNullOrEmpty(locationId)) {
            queryString.append("&location_id=").append(locationId);
        }
        if (!StringUtils.isNullOrEmpty(nextPageToken)) {
            queryString.append("&next_page_token=").append(nextPageToken);
        }
        JsonNode response = doGetWithRetry(String.format(ZOOM_ROOMS_URL, roomRequestPageSize) + queryString);

        if (response == null) {
            return Pair.of(null, null);
        }
        return Pair.of(response, response.at("/next_page_token").asText());
    }

    /**
     * Retrieve ZoomRooms user details
     *
     * @param userId id of a room user
     * @return response JsonNode
     * @throws Exception if a communication error occurs
     */
    private JsonNode retrieveUserDetails(String userId) throws Exception {
        return doGetWithRetry(String.format(ZOOM_USER_DETAILS_URL, userId));
    }

    /**
     * If addressed too frequently, Zoom API may respond with 429 code, meaning that the call rate per second was reached.
     * Normally it would rarely happen due to the request rate limit, but when it does happen - adapter must retry the
     * attempts of retrieving needed information. This method retries up to 10 times with 500ms timeout in between
     *
     * @param url to retrieve data from
     * @return JsonNode response body
     * @throws Exception if a communication error occurs
     */
    private JsonNode doGetWithRetry(String url) throws Exception {
        int retryAttempts = 0;
        Exception lastError = null;
        boolean criticalError = false;
        while (retryAttempts++ < 10 && serviceRunning) {
            try {
                return doGet(url, JsonNode.class);
            } catch (CommandFailureException e) {
                //TODO propagate 429 on top, so API Error is reported? (make optional)
                lastError = e;
                if (e.getStatusCode() != 429) {
                    // Might be 401, 403 or any other error code here so the code will just get stuck
                    // cycling this failed request until it's fixed. So we need to skip this scenario.
                    criticalError = true;
                    logger.error(String.format("ZoomRooms API error %s while retrieving %s data", e.getStatusCode(), url), e);
                    break;
                }
            } catch (FailedLoginException fle) {
                lastError = fle;
                criticalError = true;
                break;
            } catch (Exception e) {
                lastError = e;
                // if service is running, log error
                if (serviceRunning) {
                    criticalError = true;
                    logger.error(String.format("ZoomRooms API error while retrieving %s data", url), e);
                }
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }

        if (retryAttempts == 10 && serviceRunning || criticalError) {
            // if we got here, all 10 attempts failed, or this is a login error that doesn't imply retry attempts
            if (lastError instanceof CommandFailureException) {
                int code = ((CommandFailureException)lastError).getStatusCode();
                if (code == HttpStatus.UNAUTHORIZED.value() || code == HttpStatus.FORBIDDEN.value()) {
                    String errorMessage = String.format("Unauthorized to perform the request %s: %s", url, lastError.getLocalizedMessage());
                    transformAndSaveException(new FailedLoginException(errorMessage));
                    return null;
                }
                transformAndSaveException(lastError);
            } else if (lastError instanceof FailedLoginException) {
                String errorMessage = String.format("Unauthorized to perform the request %s: %s", url, lastError.getLocalizedMessage());
                transformAndSaveException(new FailedLoginException(errorMessage));
                throw lastError;
            } else {
                transformAndSaveException(lastError);
            }
        }
        return null;
    }

    /**
     * Retrieve zoom rooms with support of the ZoomRoom API pagination (next page token)
     *
     * @param locationId to filter rooms based on locations
     * @param zoomRooms to save all retrieved rooms to
     * @throws Exception if any communication error occurs
     * */
    private void processPaginatedZoomRoomsRetrieval(String locationId, List<AggregatedDevice> zoomRooms) throws Exception {
        boolean hasNextPage = true;
        String nextPageToken = null;
        Pair<JsonNode, String> response;
        while(hasNextPage) {
            logDebugMessage(String.format("Receiving page with next_page_token: %s", nextPageToken));
            response = retrieveZoomRooms(locationId, nextPageToken);
            if (response.getLeft() == null) {
                return;
            }
            nextPageToken = response.getRight();
            hasNextPage = StringUtils.isNotNullOrEmpty(nextPageToken);
            zoomRooms.addAll(aggregatedDeviceProcessor.extractDevices(response.getLeft()));
        }
    }

    /**
     * Process the response that implies possibility of having more than 1 page in total.
     * Whenever there are more pages - next_page_token property is used for the next page reference
     *
     * @param url to fetch
     * @param pageSize to have consistent page size response
     * @param processor interface that grants correct page response processing
     * @throws Exception if communication error occurs
     * */
    private void processPaginatedResponse(String url, int pageSize, PaginatedResponseProcessor processor) throws Exception {
        JsonNode roomLocations;
        boolean hasNextPage = true;
        String nextPageToken = null;
        while(hasNextPage) {
            logDebugMessage(String.format("Receiving page with next_page_token: %s", nextPageToken));
            if (StringUtils.isNullOrEmpty(nextPageToken)) {
                roomLocations = doGetWithRetry(String.format(url, pageSize));
            } else {
                roomLocations = doGetWithRetry(String.format(url + "&next_page_token=" + nextPageToken, pageSize));
            }
            if (roomLocations == null) {
                hasNextPage = false;
                continue;
            }
            nextPageToken = roomLocations.at("/next_page_token").asText();
            hasNextPage = StringUtils.isNotNullOrEmpty(nextPageToken);
            processor.process(roomLocations);
        }
    }
    /**
     * Populate ZoomRooms with properties: metrics, devices, controls etc.
     *
     * @param roomId Id of zoom room
     * @throws Exception if any error occurs
     */
    private void populateDeviceDetails(String roomId) throws Exception {
        logDebugMessage("Fetching room details for device " + roomId);
        AggregatedDevice aggregatedZoomRoomDevice = aggregatedDevices.get(roomId);
        if (aggregatedZoomRoomDevice == null) {
            logDebugMessage("Unable to find cached room with id " + roomId);
            return;
        }
        // To restore properties that were here before, but to override the rest
        Map<String, String> properties = new HashMap<>(aggregatedZoomRoomDevice.getProperties());

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

        if (properties.containsKey(METRICS_ROOM_STATUS) && properties.get(METRICS_ROOM_STATUS).equals("InMeeting")) {
            retrieveZoomRoomMetricsDetails(roomId, properties);
            setRoomInCall(aggregatedZoomRoomDevice, true);
        } else {
            cleanupStaleProperties(properties, LIVE_MEETING_GROUP);
            setRoomInCall(aggregatedZoomRoomDevice, false);
        }

        if (!excludePropertyGroups.contains("RoomUserDetails")) {
            populateRoomUserDetails(aggregatedZoomRoomDevice.getSerialNumber(), properties);
        }
        List<AdvancedControllableProperty> controllableProperties = aggregatedZoomRoomDevice.getControllableProperties();
        if (!excludePropertyGroups.contains("RoomDevices")) {
            retrieveGroupedRoomDevicesInformation(roomId, properties);
        }
        if (!excludePropertyGroups.contains("RoomControlSettings")) {
            populateRoomSettings(roomId, properties, controllableProperties);
        }

        if (includeRoomDevices) {
            updateAndRetrieveRoomDevices(roomId);
        }

        aggregatedZoomRoomDevice.setProperties(properties);
        aggregatedZoomRoomDevice.setControllableProperties(controllableProperties);
    }

    /**
     * Set zoom room in call status
     *
     * @param aggregatedZoomRoomDevice device to change inCall status for
     * @param inCall whether the device is in call or not
     * */
    private synchronized void setRoomInCall(AggregatedDevice aggregatedZoomRoomDevice, boolean inCall) {
        List<Statistics> statistics = aggregatedZoomRoomDevice.getMonitoredStatistics();
        String roomId = aggregatedZoomRoomDevice.getDeviceId();
        // if device is in the meeting - attempt to retrieve meeting details from the detailed metrics
        if (statistics == null) {
            statistics = new ArrayList<>();
            aggregatedZoomRoomDevice.setMonitoredStatistics(statistics);
        }
        statistics.clear();

        EndpointStatistics endpointStatistics = new EndpointStatistics();
        endpointStatistics.setInCall(inCall);
        statistics.add(endpointStatistics);

        // Update room devices also
        if (includeRoomDevicesInCalls) {
            aggregatedDevices.entrySet().stream().filter(deviceEntry -> deviceEntry.getKey().startsWith(ROOM_DEVICE_ID_PREFIX))
                    .map(Map.Entry::getValue).forEach(aggregatedDevice -> {
                Map<String, String> deviceProperties = aggregatedDevice.getProperties();
                if (deviceProperties != null && roomId.equals(deviceProperties.get("ZoomRoomId"))) {
                    applyParentEndpointStatisticsToDevice(aggregatedZoomRoomDevice, aggregatedDevice);
                }
            });
        }
    }

    /**
     * Add room user settings to device's properties. Check if retrieval is relevant based on {@link #validUserDetailsDataRetrievalPeriodTimestamps}
     * value, stored with the {@param roomUserId}.
     *
     * @param roomUserId id of the room user to populate properties for
     * @param properties map to add statistics to
     */
    private void populateRoomUserDetails(String roomUserId, Map<String, String> properties) throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        Long dataRetrievalTimestamp = validUserDetailsDataRetrievalPeriodTimestamps.get(roomUserId);
        long roomUserDetailsProperties = properties.keySet().stream().filter(s -> s.startsWith(ROOM_USER_DETAILS_GROUP)).count();
        if (roomUserDetailsProperties > 0 && dataRetrievalTimestamp != null &&
                dataRetrievalTimestamp > currentTimestamp) {
            logDebugMessage(String.format("Room User details retrieval is in cooldown. %s seconds left",
                    (dataRetrievalTimestamp - currentTimestamp) / 1000));
            return;
        }
        validUserDetailsDataRetrievalPeriodTimestamps.put(roomUserId, currentTimestamp + roomUserDetailsRetrievalTimeout);

        JsonNode roomUserDetails = retrieveUserDetails(roomUserId);
        Map<String, String> roomUserProperties = new HashMap<>();

        cleanupStaleProperties(properties, ROOM_USER_DETAILS_GROUP);

        if (roomUserDetails != null) {
            aggregatedDeviceProcessor.applyProperties(roomUserProperties, roomUserDetails, "RoomUserDetails");
            properties.putAll(roomUserProperties);
        }

        logDebugMessage("Updated ZoomRooms user details: " + roomUserProperties);
    }

    /**
     * Add room settings controls to device's properties. Check if retrieval is relevant based on {@link #validRoomDevicesDataRetrievalPeriodTimestamps}
     * value, stored with the {@param roomId}.
     *
     * @param roomId                 id of the room to populate settings for
     * @param properties             map to add statistics to
     * @param controllableProperties list of controllable properties, to add controls to
     */
    private void populateRoomSettings(String roomId, Map<String, String> properties, List<AdvancedControllableProperty> controllableProperties) throws Exception {
        if (!displayRoomSettings) {
            logDebugMessage("Room settings retrieval is switched off by displayRoomSettings property.");
            return;
        }
        Long dataRetrievalTimestamp = validRoomSettingsDataRetrievalPeriodTimestamps.get(roomId);
        long currentTimestamp = System.currentTimeMillis();
        long roomSettingsProperties = properties.keySet().stream().filter(s -> s.startsWith(ROOM_CONTROLS_ALERT_SETTINGS_GROUP) || s.startsWith(ROOM_CONTROLS_MEETING_SETTINGS_GROUP)).count();
        if ((roomSettingsProperties > 0 && dataRetrievalTimestamp != null && dataRetrievalTimestamp > currentTimestamp)) {
            logDebugMessage(String.format("Room settings retrieval is in cooldown. %s seconds left",
                    (dataRetrievalTimestamp - currentTimestamp) / 1000));
            return;
        }
        validRoomSettingsDataRetrievalPeriodTimestamps.put(roomId, currentTimestamp + roomSettingsRetrievalTimeout);

        cleanupStaleProperties(properties, ROOM_CONTROLS_ALERT_SETTINGS_GROUP, ROOM_CONTROLS_MEETING_SETTINGS_GROUP);
        cleanupStaleControls(controllableProperties, ROOM_CONTROLS_ALERT_SETTINGS_GROUP, ROOM_CONTROLS_MEETING_SETTINGS_GROUP);

        Map<String, String> settingsProperties = new HashMap<>();
        List<AdvancedControllableProperty> settingsControls = new ArrayList<>();

        JsonNode meetingSettings = retrieveRoomSettings(roomId, "meeting");
        if (meetingSettings != null) {
            aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, meetingSettings, "RoomMeetingSettings");
        }
        JsonNode alertSettings = retrieveRoomSettings(roomId, "alert");
        if (alertSettings != null) {
            aggregatedDeviceProcessor.applyProperties(settingsProperties, settingsControls, alertSettings, "RoomAlertSettings");
        }

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
        /** /TODO */

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
        logDebugMessage("Updated ZoomRooms room settings: " + settingsProperties);
    }

    /**
     * Types of devices: ZoomRoomsComputer, Controller, SchedulingDisplay, ZoomRoomsControlSystem, CompanionWhiteboard
     * Retrieve registered zoom room devices information, group it by type.
     * Calculate number of online/offline devices, display online/offline devices operating systems and app versions.
     *
     * @param roomId     to get devices for
     * @param properties to save properties to
     * @throws Exception if any error occurs
     */
    private void retrieveGroupedRoomDevicesInformation(String roomId, Map<String, String> properties) throws Exception {
        Long dataRetrievalTimestamp = validRoomDevicesDataRetrievalPeriodTimestamps.get(roomId);
        long currentTimestamp = System.currentTimeMillis();
        long roomDevicesProperties = properties.keySet().stream().filter(s -> s.startsWith(ROOM_DEVICES_GROUP)).count();
        if (roomDevicesProperties > 0 && dataRetrievalTimestamp != null && dataRetrievalTimestamp > currentTimestamp) {
            logDebugMessage(String.format("Room devices retrieval is in cooldown. %s seconds left",
                    (dataRetrievalTimestamp - currentTimestamp) / 1000));
            return;
        }
        validRoomDevicesDataRetrievalPeriodTimestamps.put(roomId, currentTimestamp + roomDevicesRetrievalTimeout);

        JsonNode devices = retrieveRoomDevices(roomId);
        Map<String, List<Map<String, String>>> deviceGroups = new HashMap<>();

        if (devices != null && devices.isArray()) {
            for (JsonNode deviceNode : devices) {
                Map<String, String> roomDeviceProperties = new HashMap<>();
                if (deviceNode != null) {
                    aggregatedDeviceProcessor.applyProperties(roomDeviceProperties, deviceNode, "RoomDevice");
                    aggregatedDevices.get(roomId).setDeviceOnline(DeviceStatus.isOnline(deviceNode.at("/status").asText()));
                }

                String deviceType = roomDeviceProperties.get(DEVICE_TYPE_PROPERTY);
                if (!deviceGroups.containsKey(deviceType)) {
                    deviceGroups.put(deviceType, new ArrayList<>());
                }
                deviceGroups.get(deviceType).add(roomDeviceProperties);
            }

            cleanupStaleProperties(properties, ROOM_DEVICES_GROUP);
            // Process device groups
            // Key is group, value is list of mapped properties
            deviceGroups.forEach((key, value) -> {
                List<String> onlineAppVersions = new ArrayList<>();
                List<String> offlineAppVersions = new ArrayList<>();
                List<String> onlineDeviceSystems = new ArrayList<>();
                List<String> offlineDeviceSystems = new ArrayList<>();
                int onlineDevicesTotal = 0;
                int offlineDevicesTotal = 0;
                for (Map<String, String> props : value) {
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
        logDebugMessage("Updated ZoomRooms devices properties: " + devices);
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
        if (aggregatedDevice == null) {
            return;
        }
        List<AdvancedControllableProperty> advancedControllableProperties = aggregatedDevice.getControllableProperties();
        Map<String, String> properties = aggregatedDevice.getProperties();

        advancedControllableProperties.stream().filter(advancedControllableProperty ->
                advancedControllableProperty.getName().equals(propertyName)).findFirst()
                .ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(value));
        properties.put(propertyName, value);

        if (propertyName.startsWith(ROOM_CONTROLS_GROUP)) {
            if (propertyName.endsWith(LEAVE_CURRENT_MEETING_PROPERTY) || propertyName.endsWith(END_CURRENT_MEETING_PROPERTY)) {
                properties.put(METRICS_ROOM_STATUS, "Available");

                // Need to cleanup live meeting information an remove metrics data from
                cleanupStaleProperties(properties, LIVE_MEETING_GROUP);
                zoomRoomsMetricsData.get(roomId).put(METRICS_ROOM_STATUS, "Available");
            } else if (propertyName.endsWith(START_ROOM_PMI_CONTROL_PROPERTY)) {
                properties.put(METRICS_ROOM_STATUS, "Connecting");
                zoomRoomsMetricsData.get(roomId).put(METRICS_ROOM_STATUS, "Connecting");
            }
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
        long currentTimestamp = System.currentTimeMillis();
        if (zoomRoomsMetricsData.size() > 0 && validMetricsDataRetrievalPeriodTimestamp > currentTimestamp) {
            logDebugMessage(String.format("Metrics retrieval is in cooldown. %s seconds left",
                    (validMetricsDataRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            return;
        }
        if (!includeRoomsMetrics) {
            logDebugMessage("includeRoomsMetrics is set to false, skipping metrics retrieval.");
            return;
        }
        validMetricsDataRetrievalPeriodTimestamp = currentTimestamp + metricsRetrievalTimeout;
        try {
            processPaginatedResponse(ZOOM_ROOMS_METRICS_URL, roomMetricsPageSize, (roomsMetrics) -> {
                if (!roomsMetrics.isNull() && roomsMetrics.has("zoom_rooms")) {
                    for (JsonNode metric : roomsMetrics.get("zoom_rooms")) {
                        Map<String, String> metricsData = new HashMap<>();
                        if (metric != null) {
                            String roomId = metric.at("/id").asText();
                            aggregatedDeviceProcessor.applyProperties(metricsData, metric, "ZoomRoomMetrics");
                            zoomRoomsMetricsData.put(roomId, metricsData);

                            String loadDate = metricsData.get("Metrics#LastStartTime");

                            if (loadDate != null) {
                                Instant loadDateParsed = Instant.parse(loadDate);
                                Duration uptime = Duration.between(loadDateParsed, Instant.now());

                                // Cant get seconds due to the language version
                                metricsData.put(METRICS_DATA_DEVICE_UPTIME, normalizeUptime(uptime.toMinutes() * 60));
                                metricsData.put(METRICS_DATA_DEVICE_UPTIME_MIN, String.valueOf(uptime.toMinutes()));
                            }
                            metricsData.put(METRICS_DATA_RETRIEVED_TIME, dateFormat.format(new Date()));
                        }
                    }
                }
            });

            logDebugMessage("Updated ZoomRooms metrics entries: " + zoomRoomsMetricsData);
        } catch (CommandFailureException ex) {
            if (ex.getStatusCode() == 429) {
                logger.warn(String.format("Maximum daily rate limit for %s API was reached.", ZOOM_ROOMS_METRICS_URL), ex);
            } else {
                throw ex;
            }
        }
        knownErrors.remove(ROOMS_METRICS_RETRIEVAL_ERROR_KEY);
    }

    /**
     * Retrieve detailed metrics information for the given room, including meeting details
     *
     * @param roomId     of the room to get info for
     * @param properties map to save data to
     * @throws Exception if a communication error occurs
     */
    private void retrieveZoomRoomMetricsDetails(String roomId, Map<String, String> properties) throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        Long dataRetrievalTimestamp = validLiveMeetingsDataRetrievalPeriodTimestamps.get(roomId);
        if (dataRetrievalTimestamp != null && dataRetrievalTimestamp > currentTimestamp) {
            logDebugMessage(String.format("Meeting metrics retrieval is in cooldown. %s seconds left",
                    (dataRetrievalTimestamp - currentTimestamp) / 1000));
            return;
        }
        // Metrics retrieval timeout is used so this information is retrieve once per general metrics refresh period.
        // First full metrics payload is retrieved, then the details (if necessary), an only once. Next iteration
        // will happen after general metrics data refreshed.
        validLiveMeetingsDataRetrievalPeriodTimestamps.put(roomId, currentTimestamp + liveMeetingDetailsRetrievalTimeout);

        // Need to cleanup stale properties before checking whether it is generally allowed to fetch these properties anymore.
        // So if it isn't allowed - properties are removed for good.
        cleanupStaleProperties(properties, LIVE_MEETING_GROUP);

        if (metricsRateLimitRemaining == null || metricsRateLimitRemaining < liveMeetingDetailsDailyRequestRateThreshold) {
            logDebugMessage(String.format("Skipping collection of meeting details for room %s. Remaining metrics rate limit: %s", roomId, metricsRateLimitRemaining));
            properties.put(LIVE_MEETING_GROUP_WARNING, String.format("Daily request rate threshold of %s for the Meeting Dashboard API was reached.", liveMeetingDetailsDailyRequestRateThreshold));
            return;
        }
        if (!displayLiveMeetingDetails) {
            logDebugMessage(String.format("Skipping collection of meeting details for room %s. showLiveMeetingDetails parameter is set to false", roomId));
            return;
        }

        try {
            JsonNode roomsMetrics = doGet(String.format(ZOOM_ROOM_METRICS_DETAILS_URL, roomId), JsonNode.class);
            if (roomsMetrics != null && !roomsMetrics.isNull() && roomsMetrics.has("live_meeting")) {
                aggregatedDeviceProcessor.applyProperties(properties, roomsMetrics, "ZoomRoomMeeting");
                properties.put(LIVE_MEETING_DATA_RETRIEVED_TIME, dateFormat.format(new Date()));
            }
            logDebugMessage("Retrieve ZoomRooms deeting details for room: " + roomId);
        } catch (CommandFailureException ex) {
            if (ex.getStatusCode() == 429) {
                logger.warn(String.format("Maximum daily rate limit for %s API was reached.", ZOOM_ROOM_METRICS_DETAILS_URL), ex);
            } else {
                throw ex;
            }
        }
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
        return doGetWithRetry(ZOOM_ROOM_ACCOUNT_SETTINGS_URL + "?setting_type=" + type);
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
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link ZoomRoomsAggregatorCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        // If the adapter is destroyed out of order, we need to make sure the device isn't paused here
        if (validRetrieveStatisticsTimestamp > 0L) {
            devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
        } else {
            devicePaused = false;
        }
    }

    /**
     * Update statistics retrieval timestamp
     * */
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
    }

    /**
     * Remove an entry from the specified map, if key starts with one of the options provided
     *
     * @param properties    map to remove property from
     * @param propertyNames options to use when defining which properties to remove
     */
    private void cleanupStaleProperties(Map<String, String> properties, String... propertyNames) {
        properties.keySet().removeIf(s -> {
            for (String propertyName : propertyNames) {
                if (s.startsWith(propertyName)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Remove an entry from the specified list of controllable ptoperties, if property name starts with one of the options provided
     *
     * @param advancedControllableProperties list to remove object from
     * @param controlNames                   options to use when defining which properties to remove
     */
    private void cleanupStaleControls(List<AdvancedControllableProperty> advancedControllableProperties, String... controlNames) {
        advancedControllableProperties.removeIf(advancedControllableProperty -> {
            for (String controlName : controlNames) {
                if (advancedControllableProperty.getName().startsWith(controlName)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Normalize value of a setting control to represent real values - true or false.
     *
     * @param value raw object coming from Symphony
     * @return {@link String} value of 'true' or 'false' based on the initial value
     */
    private String normalizeSettingData(Object value) {
        return "0".equals(String.valueOf(value)) ? "false" : "true";
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptimeSeconds value in seconds
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(long uptimeSeconds) {
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }

    /**
     * Generate OAuth access token by issuing {@link #ZOOM_ROOM_OAUTH_URL} endpoint with Basic authentication header
     * containing Base64 encoded clientId:clientSecret pair (login:password).
     *
     * @throws Exception if credentials are incorrect or any other communication error occurs
     * @since 1.1.0
     * */
    private void generateAccessToken() throws Exception {
        if (StringUtils.isNullOrEmpty(accountId)) {
            String message = "Unable to log in using OAuth: no accountId provided";
            knownErrors.put(LOGIN_ERROR_KEY, message);
            throw new IllegalArgumentException(message);
        }
        if (StringUtils.isNullOrEmpty(getLogin())) {
            String message = "Unable to log in using OAuth: no clientId provided";
            knownErrors.put(LOGIN_ERROR_KEY, message);
            throw new IllegalArgumentException(message);
        }
        if (StringUtils.isNullOrEmpty(getPassword())) {
            String message = "Unable to log in using OAuth: no clientSecret provided";
            knownErrors.put(LOGIN_ERROR_KEY, message);
            throw new IllegalArgumentException(message);
        }
        String requestUrl = String.format("%s://%s/%s", getProtocol(), zoomOAuthHostname, ZOOM_ROOM_OAUTH_URL + String.format(ZOOM_ROOM_OAUTH_PARAMS_URL, accountId));
        logDebugMessage("Attempting to generate access token with requestUrl " + requestUrl);
        JsonNode response = null;
        try {
            response = doPost(requestUrl, null, JsonNode.class);
        } catch (Exception e) {
            String message = "Authorization Failed: " + e.getMessage();
            knownErrors.put(LOGIN_ERROR_KEY, message);
            throw new FailedLoginException(message);
        }
        if (response == null) {
            String message = String.format("Failed to authorize account with id %s through OAuth chain. Please check client data or OAuth application settings.", accountId);
            knownErrors.put(LOGIN_ERROR_KEY, message);
            throw new FailedLoginException(message);
        }
        Map<String, String> oauthResponseData = new HashMap<>();
        aggregatedDeviceProcessor.applyProperties(oauthResponseData, response, "OAuthResponse");
        if (oauthResponseData.isEmpty() || !oauthResponseData.containsKey("AccessToken")) {
            String message = String.format("Failed to retrieve an OAuth access token for account with id %s. Please check client data or OAuth application settings.", accountId);
            knownErrors.put(LOGIN_ERROR_KEY, message);
            throw new FailedLoginException(message);
        }
        oAuthAccessToken = oauthResponseData.get("AccessToken");
        oauthTokenExpiresIn = Integer.parseInt(oauthResponseData.get("ExpiresIn")) * 1000L;
        oauthTokenGeneratedTimestamp = System.currentTimeMillis();
        knownErrors.remove(LOGIN_ERROR_KEY);
    }

    private void transformAndSaveException(Exception e) {
        // TODO define logic to transform an exception to a type/message entry for knownErrors
        logger.error("An error occurred while performing operation", e);
    }
    /**
     * Multiple statistics include error data, in order to display it properly - messages must be limited
     * by length. This method cuts messages to a max length passed.
     *
     * @param originalMessage string message to crop
     * @param length max length value to adjust message to
     * @return String value of cropped string
     * @since 1.1.0
     */
    private String limitErrorMessageByLength(String originalMessage, int length) {
        if (originalMessage == null) {
            return "N/A";
        }
        int messageLength = originalMessage.length();
        String resultMessage =  originalMessage.substring(0, Math.min(messageLength, length));
        if (messageLength > length) {
            return resultMessage + "...";
        }
        return resultMessage;
    }

    /**
     * Logging debug message with checking if it's enabled first
     *
     * @param message to log
     * */
    private void logDebugMessage(String message) {
        if (logger.isDebugEnabled()) {
            logger.debug(message);
        }
    }
}
