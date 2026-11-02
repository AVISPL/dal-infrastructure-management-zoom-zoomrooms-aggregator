/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * Base class of the simulator-backed tests (skill dal-simulator-tests): runs the adapter against the Zoom Rooms
 * simulator instance given by the system properties, and drives the simulator through its admin API only.
 * <p>
 * Required: {@code -Dsimulator.host}, {@code -Dsimulator.adminPort}, {@code -Dsimulator.basePort},
 * {@code -Dsimulator.instance} (no default, so two runs never share an instance by accident). The adapter connects to
 * {@code basePort + instance}. Every admin call names that instance; other instances are never reset or faulted.
 * <p>
 * Run: {@code mvn -o test -Dgroups=simulatorTest -Dsimulator.host=localhost -Dsimulator.adminPort=8013
 * -Dsimulator.basePort=8013 -Dsimulator.instance=0}
 */
@Tag("simulatorTest")
public abstract class ZoomRoomsSimulatorTestBase {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** Adapter HTTP timeout: short, so held (noResponse) requests fail fast. */
    protected static final int ADAPTER_HTTP_TIMEOUT_MS = 5000;

    /**
     * The adapter's shortest refresh period for the rooms list, metrics, user details, room settings and room devices
     * (its setters clamp every retrieval timeout to at least 30 s). One collection interval in these tests.
     */
    protected static final long COLLECTION_INTERVAL_MS = 30_000;

    /** Upper bound for "within one collection interval": the interval, plus a cycle and the poll period. */
    protected static final long ONE_INTERVAL_WAIT_MS = COLLECTION_INTERVAL_MS + 30_000;

    protected static final long POLL_PERIOD_MS = 2000;

    protected static String host;
    protected static int adminPort;
    protected static int basePort;
    protected static int instance;
    private static String adminBase;
    private static RestTemplate http;

    private final List<ZoomRoomsAggregatorCommunicator> communicators = new ArrayList<>();

    @BeforeAll
    static void readSimulatorProperties() {
        host = requiredProperty("simulator.host");
        adminPort = Integer.parseInt(requiredProperty("simulator.adminPort"));
        basePort = Integer.parseInt(requiredProperty("simulator.basePort"));
        instance = Integer.parseInt(requiredProperty("simulator.instance"));
        adminBase = "http://" + host + ":" + adminPort + "/api/simulator";
        http = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        JsonNode info = admin(HttpMethod.GET, "/info", null, 200);
        boolean instanceListed = false;
        for (JsonNode entry : info.path("instances")) {
            if (entry.path("instance").asInt() == instance) {
                instanceListed = true;
                Assertions.assertEquals(basePort + instance, entry.path("port").asInt(),
                        "The adapter connects to basePort + instance, but the simulator lists another port for it");
            }
        }
        Assertions.assertTrue(instanceListed, "Simulator instance " + instance + " does not exist: " + info);
    }

    @BeforeEach
    void resetInstance() {
        admin(HttpMethod.POST, "/reset", null, 204);
    }

    @AfterEach
    void destroyCommunicatorsAndClearFaults() {
        for (ZoomRoomsAggregatorCommunicator communicator : communicators) {
            try {
                communicator.destroy();
            } catch (Exception e) {
                // keep cleaning up
            }
        }
        communicators.clear();
        clearFaults();
    }

    @AfterAll
    static void leaveInstanceClean() {
        if (adminBase != null) {
            admin(HttpMethod.POST, "/reset", null, 204);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("System property -D" + name + " is required for simulator tests");
        }
        return value.trim();
    }

    // ------------------------------------------------------------------------------------------------ admin API

    /**
     * Calls the admin API for this test's instance ({@code ?instance=} is always added) and checks the status.
     */
    protected static JsonNode admin(HttpMethod method, String path, Object body, int expectedStatus) {
        String url = adminBase + path + (path.contains("?") ? "&" : "?") + "instance=" + instance;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        String payload = null;
        try {
            if (body instanceof String) {
                payload = (String) body;
            } else if (body != null) {
                payload = MAPPER.writeValueAsString(body);
            }
            ResponseEntity<String> response = http.exchange(url, method, new HttpEntity<>(payload, headers), String.class);
            Assertions.assertEquals(expectedStatus, response.getStatusCode().value(),
                    method + " " + url + " answered " + response.getStatusCode() + ": " + response.getBody());
            String responseBody = response.getBody();
            return responseBody == null || responseBody.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new IllegalStateException("Admin call failed: " + method + " " + url, e);
        }
    }

    /**
     * Applies a fault preset to this instance: {@code {"preset", "instance", "target", "trigger", "value"}}.
     *
     * @return the rule id
     */
    protected String applyPreset(String preset, String target, String trigger, Integer value) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("preset", preset);
        rule.put("target", target);
        if (trigger != null) {
            rule.put("trigger", trigger);
        }
        if (value != null) {
            rule.put("value", value);
        }
        return addRule(rule);
    }

    /**
     * Adds a raw fault rule to this instance (never "all").
     *
     * @return the rule id
     */
    protected String addRule(ObjectNode rule) {
        rule.put("instance", instance);
        return admin(HttpMethod.POST, "/faults", rule, 201).path("id").asText();
    }

    protected void clearFaults() {
        admin(HttpMethod.DELETE, "/faults", null, 204);
    }

    protected void removeRule(String id) {
        admin(HttpMethod.DELETE, "/faults/" + id, null, 204);
    }

    protected JsonNode rule(String id) {
        for (JsonNode rule : admin(HttpMethod.GET, "/faults", null, 200)) {
            if (id.equals(rule.path("id").asText())) {
                return rule;
            }
        }
        throw new AssertionError("No fault rule " + id);
    }

    /** The instance's state ({@code GET /api/simulator/state}). */
    protected JsonNode state() {
        return admin(HttpMethod.GET, "/state", null, 200).get(0).path("state");
    }

    protected JsonNode patchState(String json) {
        return admin(HttpMethod.PATCH, "/state", json, 200).get(0).path("state");
    }

    /** The instance's request log (the last simulator.request-log-size entries), sorted oldest first. */
    protected List<JsonNode> requests() {
        List<JsonNode> result = new ArrayList<>();
        admin(HttpMethod.GET, "/state?include=requests", null, 200).get(0).path("requests").forEach(result::add);
        result.sort((a, b) -> Instant.parse(a.path("time").asText()).compareTo(Instant.parse(b.path("time").asText())));
        return result;
    }

    /** Requests logged after {@code since} whose target matches. */
    protected List<JsonNode> requestsSince(Instant since, Predicate<String> target) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode request : requests()) {
            if (!Instant.parse(request.path("time").asText()).isBefore(since) && target.test(request.path("target").asText())) {
                result.add(request);
            }
        }
        return result;
    }

    /** Replaces the fleet with a generated one (smaller than the initial 10 x 100 when a test needs no scale). */
    protected JsonNode generateFleet(int rooms, int devicesPerRoom) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("rooms", rooms);
        body.put("devicesPerRoom", devicesPerRoom);
        body.put("replace", true);
        return admin(HttpMethod.POST, "/fleet/generate", body, 201);
    }

    protected JsonNode room(String roomId) {
        for (JsonNode room : state().path("rooms")) {
            if (roomId.equals(room.path("room_id").asText())) {
                return room;
            }
        }
        throw new AssertionError("No room " + roomId + " in the simulator state");
    }

    protected JsonNode user(String userId) {
        for (JsonNode user : state().path("users")) {
            if (userId.equals(user.path("id").asText())) {
                return user;
            }
        }
        throw new AssertionError("No user " + userId + " in the simulator state");
    }

    // ------------------------------------------------------------------------------------------------ adapter

    /**
     * A communicator configured for this simulator instance, with the credentials the simulator shows
     * ({@code GET /api/simulator/credentials}). The customizer runs before {@code init()}.
     */
    protected ZoomRoomsAggregatorCommunicator newCommunicator(Consumer<ZoomRoomsAggregatorCommunicator> customizer) throws Exception {
        Map<String, String> credentials = credentials();
        ZoomRoomsAggregatorCommunicator communicator = new ZoomRoomsAggregatorCommunicator();
        communicators.add(communicator);
        communicator.setHost(host);
        communicator.setPort(basePort + instance);
        communicator.setProtocol("http");
        communicator.setLogin(credentials.get("Login"));
        communicator.setPassword(credentials.get("Password"));
        communicator.setAccountId(credentials.get("accountId"));
        communicator.setZoomOAuthHostname(host + ":" + (basePort + instance));
        communicator.setTimeout(ADAPTER_HTTP_TIMEOUT_MS);
        communicator.setIncludeRoomDevices(true);
        communicator.setDisplayAccountSettings(true);
        communicator.setDisplayRoomSettings(true);
        communicator.setDisplayLiveMeetingDetails(true);
        if (customizer != null) {
            customizer.accept(communicator);
        }
        communicator.init();
        return communicator;
    }

    protected ZoomRoomsAggregatorCommunicator newCommunicator() throws Exception {
        return newCommunicator(null);
    }

    /** Credentials by the first word of their Symphony label (Login, Password, accountId). */
    protected Map<String, String> credentials() {
        Map<String, String> result = new HashMap<>();
        JsonNode sets = admin(HttpMethod.GET, "/credentials", null, 200).get(0).path("sets");
        for (JsonNode field : sets.get(0).path("fields")) {
            result.put(field.path("label").asText().split(" ")[0], field.path("value").asText());
        }
        return result;
    }

    /** One poll of the aggregated devices; null when the call throws. */
    protected static List<AggregatedDevice> pollOnce(ZoomRoomsAggregatorCommunicator communicator) {
        try {
            return communicator.retrieveMultipleStatistics();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Polls retrieveMultipleStatistics (which also keeps the background collection running) until the condition holds
     * for the returned list, or fails after the timeout.
     */
    protected static List<AggregatedDevice> pollUntil(ZoomRoomsAggregatorCommunicator communicator, String what, long timeoutMs,
            Predicate<List<AggregatedDevice>> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<AggregatedDevice> last = null;
        while (System.currentTimeMillis() < deadline) {
            List<AggregatedDevice> devices = pollOnce(communicator);
            if (devices != null) {
                last = devices;
                if (condition.test(devices)) {
                    return devices;
                }
            }
            Thread.sleep(POLL_PERIOD_MS);
        }
        throw new AssertionError("Timed out after " + timeoutMs + " ms waiting for: " + what + ". Last poll: "
                + (last == null ? "none" : last.size() + " devices"));
    }

    /** Waits until a condition holds, polling the adapter meanwhile so collection keeps running. */
    protected static void waitUntil(ZoomRoomsAggregatorCommunicator communicator, String what, long timeoutMs, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (communicator != null) {
                pollOnce(communicator);
            }
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(POLL_PERIOD_MS);
        }
        throw new AssertionError("Timed out after " + timeoutMs + " ms waiting for: " + what);
    }

    /** Keeps polling for the given time; each poll's result goes to the observer (null when the call threw). */
    protected static void pollFor(ZoomRoomsAggregatorCommunicator communicator, long durationMs, Consumer<List<AggregatedDevice>> observer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            observer.accept(pollOnce(communicator));
            Thread.sleep(POLL_PERIOD_MS);
        }
    }

    /**
     * Waits for the first full collection: every simulator room reported with its details (metrics, user, settings),
     * and room devices for every room that has some. Exact device counts are the tests' assertions, not this wait.
     */
    protected List<AggregatedDevice> waitForFullCollection(ZoomRoomsAggregatorCommunicator communicator) throws Exception {
        JsonNode state = state();
        final int rooms = state.path("rooms").size();
        final List<String> roomsWithDevices = new ArrayList<>();
        for (JsonNode room : state.path("rooms")) {
            if (room.path("devices").size() > 0) {
                roomsWithDevices.add(room.path("room_id").asText());
            }
        }
        return pollUntil(communicator, rooms + " rooms with details and their room devices", 120_000, list -> rooms(list).size() == rooms
                && rooms(list).stream().allMatch(room -> room.getProperties() != null
                && room.getProperties().containsKey("Metrics#Email")
                && room.getProperties().containsKey("RoomUserDetails#Email")
                && room.getProperties().keySet().stream().anyMatch(key -> key.startsWith("RoomControlsMeetingSettings#")))
                && roomsWithDevices.stream().allMatch(roomId -> roomDevices(list).stream()
                .anyMatch(device -> roomId.equals(device.getProperties().get("ZoomRoomId")))));
    }

    /** Request log lines (target and status) whose target contains the text, for failure messages. */
    protected String requestEvidence(String contains, int max) {
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (JsonNode request : requests()) {
            String target = request.path("target").asText();
            if (target.contains(contains) && count++ < max) {
                result.append("\n  ").append(request.path("time").asText()).append(' ').append(target)
                        .append(" -> ").append(request.path("status").asText());
            }
        }
        return result.toString();
    }

    protected static List<AggregatedDevice> rooms(List<AggregatedDevice> devices) {
        List<AggregatedDevice> result = new ArrayList<>();
        for (AggregatedDevice device : devices) {
            if (!isRoomDevice(device)) {
                result.add(device);
            }
        }
        return result;
    }

    protected static List<AggregatedDevice> roomDevices(List<AggregatedDevice> devices) {
        List<AggregatedDevice> result = new ArrayList<>();
        for (AggregatedDevice device : devices) {
            if (isRoomDevice(device)) {
                result.add(device);
            }
        }
        return result;
    }

    protected static boolean isRoomDevice(AggregatedDevice device) {
        return device.getProperties() != null && device.getProperties().containsKey("ZoomRoomId");
    }

    protected static AggregatedDevice byId(List<AggregatedDevice> devices, String deviceId) {
        for (AggregatedDevice device : devices) {
            if (deviceId.equals(device.getDeviceId())) {
                return device;
            }
        }
        return null;
    }

    protected static ExtendedStatistics aggregatorStatistics(ZoomRoomsAggregatorCommunicator communicator) throws Exception {
        return (ExtendedStatistics) communicator.getMultipleStatistics().get(0);
    }

    protected static AdvancedControllableProperty control(List<AdvancedControllableProperty> controls, String name) {
        if (controls == null) {
            return null;
        }
        for (AdvancedControllableProperty control : controls) {
            if (name.equals(control.getName())) {
                return control;
            }
        }
        return null;
    }

    /** Switch value as a boolean: the adapter reports "true"/"false" from the API and "1"/"0" after a control. */
    protected static boolean switchOn(Object value) {
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** Flattens an object of objects into "a.b.c" -> leaf. */
    protected static Map<String, JsonNode> flatten(JsonNode node) {
        Map<String, JsonNode> result = new HashMap<>();
        flatten("", node, result);
        return result;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, JsonNode> result) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                flatten(prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), field.getValue(), result);
            }
        } else {
            result.put(prefix, node);
        }
    }

    /** Keys whose values differ between two flattened maps. */
    protected static Map<String, String> diff(Map<String, JsonNode> before, Map<String, JsonNode> after) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : after.entrySet()) {
            JsonNode old = before.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) {
                result.put(entry.getKey(), old + " -> " + entry.getValue());
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                result.put(key, before.get(key) + " -> (removed)");
            }
        }
        return result;
    }
}
