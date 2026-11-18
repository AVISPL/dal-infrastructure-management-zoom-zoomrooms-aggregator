/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * Dashboard (metrics) API rate limits, through the simulator's limiter (README "Rate limit"): per-minute 429
 * {@code rateLimited} with X-RateLimit-Reset (no Retry-After), daily 429 {@code dailyLimit} with Retry-After (ISO 8601,
 * next 00:00 GMT; documented, developer answer 2026-11-02). Only the metrics endpoints are limited.
 */
public class ZoomRoomsRateLimitSimulatorTest extends ZoomRoomsSimulatorTestBase {

    private static final String METRICS_LIST = "GET /v2/metrics/zoomrooms?";

    /** The adapter's documented retry count for a 429 (doGetWithRetry: up to 10 attempts, 200 ms apart). */
    private static final int MAX_ATTEMPTS_PER_REFRESH = 10;

    /**
     * Per-minute limit reached: the metrics refresh fails with 429; the adapter retries a bounded number of times,
     * keeps the cached metrics and every other group, reports the remaining-limit statistic, and recovers on the first
     * refresh after the limit clears.
     */
    @Test
    public void perMinuteLimitKeepsOtherDataBacksOffBoundedAndRecovers() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        List<AggregatedDevice> established = waitForFullCollection(communicator);
        final int expected = established.size();
        final Map<String, String> retrievedTimes = metricsRetrievedTimes(established);

        patchState("{\"rateLimit\": {\"limits\": {\"Pro\": {\"perMinute\": 0}}}}");
        final Instant since = Instant.now();
        final List<String> polls = new ArrayList<>();
        waitUntil(communicator, "a 429 on the metrics list", ONE_INTERVAL_WAIT_MS, () -> {
            polls.add(String.valueOf(listSize(communicator)));
            return requestsSince(since, t -> t.startsWith(METRICS_LIST)).stream().anyMatch(r -> "429".equals(r.path("status").asText()));
        });
        pollFor(communicator, 10_000, list -> polls.add(list == null ? "threw" : String.valueOf(list.size())));

        List<AggregatedDevice> during = communicator.retrieveMultipleStatistics();
        ExtendedStatistics statistics = aggregatorStatistics(communicator);
        List<JsonNode> metricsRequests = requestsSince(since, t -> t.startsWith(METRICS_LIST));
        List<Integer> bursts = bursts(metricsRequests);
        System.out.println("[simulatorTest] per-minute limit: metrics list attempts per refresh " + bursts + ", statuses "
                + statuses(metricsRequests) + ", errors " + errorStatistics(statistics));

        for (String poll : polls) {
            Assertions.assertEquals(String.valueOf(expected), poll, "device list intact on every poll: " + polls);
        }
        for (AggregatedDevice room : rooms(during)) {
            Map<String, String> p = room.getProperties();
            Assertions.assertNotNull(p.get("Metrics#Email"), "cached metrics kept for " + room.getDeviceId());
            Assertions.assertNotNull(p.get("RoomUserDetails#Email"), "user details intact for " + room.getDeviceId());
            Assertions.assertTrue(p.keySet().stream().anyMatch(k -> k.startsWith("RoomControlsMeetingSettings#")), "settings intact for " + room.getDeviceId());
        }
        Assertions.assertTrue(statistics.getStatistics().get("DashboardMetricsDailyRateLimitRemaining").matches("\\d+"),
                "remaining limit statistic: " + statistics.getStatistics().get("DashboardMetricsDailyRateLimitRemaining"));
        for (int attempts : bursts) {
            Assertions.assertTrue(attempts <= MAX_ATTEMPTS_PER_REFRESH, "bounded retries per metrics refresh: " + bursts);
        }

        // The limit clears: the next refresh succeeds and the metrics are fresh again
        patchState("{\"rateLimit\": {\"limits\": {\"Pro\": {\"perMinute\": 10}}, \"minuteCount\": 0}}");
        pollUntil(communicator, "metrics refreshed after the limit cleared", ONE_INTERVAL_WAIT_MS + COLLECTION_INTERVAL_MS, list -> {
            for (AggregatedDevice room : rooms(list)) {
                if (room.getProperties().get("Metrics#DataRetrievedTime") == null
                        || room.getProperties().get("Metrics#DataRetrievedTime").equals(retrievedTimes.get(room.getDeviceId()))) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * Daily limit reached (dayCount = perDay): metrics answer 429 dailyLimit with X-RateLimit-Remaining 0. The adapter
     * keeps the other data, reports the remaining limit as 0, and skips live meeting details with its warning.
     */
    @Test
    public void dailyLimitKeepsOtherDataAndReportsZeroRemaining() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final int expected = waitForFullCollection(communicator).size();
        reachDailyLimit();
        final Instant since = Instant.now();
        final List<String> polls = new ArrayList<>();
        List<AggregatedDevice> devices = pollUntil(communicator, "live meeting warning on the InMeeting rooms", ONE_INTERVAL_WAIT_MS + COLLECTION_INTERVAL_MS, list -> {
            polls.add(String.valueOf(list.size()));
            for (AggregatedDevice room : rooms(list)) {
                if ("InMeeting".equals(room.getProperties().get("Metrics#RoomStatus")) && !room.getProperties().containsKey("LiveMeeting#Warning")) {
                    return false;
                }
            }
            return requestsSince(since, t -> t.startsWith("GET /v2/metrics/zoomrooms")).stream().anyMatch(r -> "429".equals(r.path("status").asText()));
        });
        ExtendedStatistics statistics = aggregatorStatistics(communicator);
        System.out.println("[simulatorTest] daily limit: metrics requests " + statuses(requestsSince(since, t -> t.startsWith("GET /v2/metrics/zoomrooms")))
                + ", errors " + errorStatistics(statistics));

        Assertions.assertEquals("0", statistics.getStatistics().get("DashboardMetricsDailyRateLimitRemaining"), "remaining daily limit");
        for (String poll : polls) {
            Assertions.assertEquals(String.valueOf(expected), poll, "device list intact on every poll: " + polls);
        }
        for (AggregatedDevice room : rooms(devices)) {
            Map<String, String> p = room.getProperties();
            Assertions.assertNotNull(p.get("Metrics#Email"), "cached metrics kept for " + room.getDeviceId());
            Assertions.assertNotNull(p.get("RoomUserDetails#Email"), "user details intact for " + room.getDeviceId());
            if ("InMeeting".equals(p.get("Metrics#RoomStatus"))) {
                Assertions.assertFalse(p.containsKey("LiveMeeting#Topic"), "no live meeting details over the threshold: " + room.getDeviceId());
            }
        }
    }

    /**
     * Daily limit: the 429 carries Retry-After (next 00:00 GMT, documented for this device), so after it the adapter
     * must not request the metrics again before that time (skill dal-simulator-tests: assert Retry-After is honoured
     * when documented). Observed over one more collection interval.
     */
    @Test
    public void dailyLimitRetryAfterIsHonoured() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        waitForFullCollection(communicator);
        reachDailyLimit();
        final Instant since = Instant.now();
        waitUntil(communicator, "the daily 429", ONE_INTERVAL_WAIT_MS, () -> requestsSince(since, t -> t.startsWith(METRICS_LIST)).stream()
                .anyMatch(r -> "429".equals(r.path("status").asText())));
        List<JsonNode> first = requestsSince(since, t -> t.startsWith(METRICS_LIST));
        Thread.sleep(3000);
        final Instant afterFirstRefresh = Instant.now();
        pollFor(communicator, COLLECTION_INTERVAL_MS + 15_000, list -> { });
        List<JsonNode> later = requestsSince(afterFirstRefresh, t -> t.startsWith("GET /v2/metrics/zoomrooms"));
        System.out.println("[simulatorTest] daily limit: first refresh " + statuses(first) + "; metrics requests in the next "
                + (COLLECTION_INTERVAL_MS + 15_000) / 1000 + " s, before Retry-After: " + later.size() + " " + statuses(later));
        Assertions.assertTrue(later.isEmpty(), later.size() + " metrics requests after the daily 429, before its Retry-After (next 00:00 GMT)");
    }

    private void reachDailyLimit() {
        int perDay = state().path("rateLimit").path("limits").path("Pro").path("perDay").asInt();
        patchState("{\"rateLimit\": {\"dayCount\": " + perDay + ", \"dayDate\": \"" + LocalDate.now(ZoneOffset.UTC) + "\"}}");
    }

    private static int listSize(ZoomRoomsAggregatorCommunicator communicator) {
        List<AggregatedDevice> devices = pollOnce(communicator);
        return devices == null ? -1 : devices.size();
    }

    private static Map<String, String> metricsRetrievedTimes(List<AggregatedDevice> devices) {
        Map<String, String> result = new HashMap<>();
        for (AggregatedDevice room : rooms(devices)) {
            result.put(room.getDeviceId(), room.getProperties().get("Metrics#DataRetrievedTime"));
        }
        return result;
    }

    /** Request counts of consecutive groups (a gap over 5 s starts a new refresh). */
    private static List<Integer> bursts(List<JsonNode> requests) {
        List<Integer> result = new ArrayList<>();
        Instant last = null;
        for (JsonNode request : requests) {
            Instant at = Instant.parse(request.path("time").asText());
            if (last == null || at.isAfter(last.plusSeconds(5))) {
                result.add(0);
            }
            result.set(result.size() - 1, result.get(result.size() - 1) + 1);
            last = at;
        }
        return result;
    }

    private static Map<String, Integer> statuses(List<JsonNode> requests) {
        Map<String, Integer> result = new HashMap<>();
        for (JsonNode request : requests) {
            result.merge(request.path("status").asText(), 1, Integer::sum);
        }
        return result;
    }

    private static List<String> errorStatistics(ExtendedStatistics statistics) {
        List<String> result = new ArrayList<>();
        statistics.getStatistics().forEach((key, value) -> {
            if (key.startsWith("Errors#")) {
                result.add(key + "=" + value);
            }
        });
        return result;
    }
}
