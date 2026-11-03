/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.security.auth.login.FailedLoginException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.error.ResourceNotReachableException;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * SYAL-4000, with the simulator README recipes ("Device-specific failures from support tickets").
 */
public class ZoomRoomsSyal4000SimulatorTest extends ZoomRoomsSimulatorTestBase {

    /**
     * (a) Every 2nd account settings request answers 500 (the alert one of each getMultipleStatistics): no
     * SpelEvaluationException, statistics still returned, each settings type requested once per call.
     */
    @Test
    public void failingSecondAccountSettingsRequestDoesNotBreakStatistics() throws Exception {
        generateFleet(1, 1);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator(c -> c.setDisplayAccountSettings(true));
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("target", "GET /v2/rooms/account_settings");
        rule.put("action", "ERROR");
        rule.put("status", 500);
        rule.put("errorTemplate", "default.json");
        rule.put("trigger", "EVERY_N");
        rule.put("value", 2);
        String ruleId = addRule(rule);
        Instant since = Instant.now();

        int calls = 4;
        for (int i = 0; i < calls; i++) {
            ExtendedStatistics statistics;
            try {
                statistics = aggregatorStatistics(communicator);
            } catch (Exception e) {
                throw new AssertionError("getMultipleStatistics threw on call " + (i + 1) + " (SYAL-4000: EL1011E): " + e, e);
            }
            Assertions.assertNotNull(statistics.getStatistics().get("AdapterVersion"), "statistics returned");
            Assertions.assertTrue(statistics.getStatistics().keySet().stream().anyMatch(key -> key.startsWith("AccountMeetingSettings#")),
                    "meeting settings (the request that succeeded) still reported");
            Assertions.assertTrue(statistics.getStatistics().keySet().stream().noneMatch(key -> key.startsWith("AccountAlertSettings#")),
                    "alert settings (the request that failed) left out, not filled with garbage");
            Assertions.assertTrue(statistics.getControllableProperties().stream().noneMatch(c -> c.getName().startsWith("AccountAlertSettings#")),
                    "no alert setting controls without data");
        }
        List<JsonNode> settingsRequests = requestsSince(since, target -> target.startsWith("GET /v2/rooms/account_settings"));
        Assertions.assertEquals(2 * calls, settingsRequests.size(), "each settings type requested once per call: " + settingsRequests);
        for (JsonNode request : settingsRequests) {
            boolean alert = request.path("target").asText().endsWith("setting_type=alert");
            Assertions.assertEquals(alert ? "500" : "200", request.path("status").asText(), request.toString());
        }
        Assertions.assertEquals(calls, rule(ruleId).path("fired").asInt(), "rule fired once per call");
    }

    /**
     * (b) The token request gets no answer (held, then dropped). The adapter must not report a login failure ("Cannot
     * login to the component"), and must give up within its HTTP timeout. The simulator's noResponse is a held request,
     * not a refused connection (README template gap); see {@link #refusedOAuthConnectionIsReportedAsResourceNotReachable()}.
     */
    @Test
    public void unansweredTokenRequestIsNotReportedAsLoginFailure() throws Exception {
        generateFleet(1, 1);
        applyPreset("noResponse", "POST /oauth/token", null, null);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();

        long start = System.currentTimeMillis();
        Exception thrown = null;
        try {
            communicator.getMultipleStatistics();
        } catch (Exception e) {
            thrown = e;
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[simulatorTest] unanswered token request: " + thrown + " after " + elapsed + " ms (adapter timeout "
                + communicator.getTimeout() + " ms)");
        Assertions.assertNotNull(thrown, "getMultipleStatistics must report the unreachable OAuth endpoint");
        Assertions.assertFalse(thrown instanceof FailedLoginException, "reported as a login failure: " + thrown);
        Assertions.assertTrue(thrown instanceof ResourceNotReachableException, "expected a connectivity error, got " + thrown);

        // Recovery: the endpoint answers again
        clearFaults();
        ExtendedStatistics statistics = aggregatorStatistics(communicator);
        Assertions.assertFalse(statistics.getStatistics().containsKey("Errors#LoginError"), "login error cleared: " + statistics.getStatistics());
        pollUntil(communicator, "rooms after recovery", ONE_INTERVAL_WAIT_MS, list -> !rooms(list).isEmpty());

        Assertions.assertTrue(elapsed < communicator.getTimeout() + 5000, "the held token request must fail within the adapter timeout ("
                + communicator.getTimeout() + " ms), took " + elapsed + " ms: the adapter waited for the simulator's no-response cap");
    }

    /**
     * (b) A real refused connection: the OAuth host points at a closed local port.
     */
    @Test
    public void refusedOAuthConnectionIsReportedAsResourceNotReachable() throws Exception {
        generateFleet(1, 1);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        final int port = closedPort;
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator(c -> c.setZoomOAuthHostname(host + ":" + port));
        Exception thrown = null;
        try {
            communicator.getMultipleStatistics();
        } catch (Exception e) {
            thrown = e;
        }
        Assertions.assertNotNull(thrown, "getMultipleStatistics must report the refused connection");
        Assertions.assertFalse(thrown instanceof FailedLoginException, "reported as a login failure: " + thrown);
        Assertions.assertTrue(thrown instanceof ResourceNotReachableException, "expected ResourceNotReachableException, got " + thrown);

        Exception fromAggregatedDevices = null;
        try {
            communicator.retrieveMultipleStatistics();
        } catch (Exception e) {
            fromAggregatedDevices = e;
        }
        Assertions.assertNotNull(fromAggregatedDevices, "retrieveMultipleStatistics reports the refused connection");
        Assertions.assertFalse(fromAggregatedDevices instanceof FailedLoginException, "retrieveMultipleStatistics: " + fromAggregatedDevices);
    }

    /**
     * Contrast: credentials rejected by the token endpoint (400 oauthInvalidClient) are a login failure.
     */
    @Test
    public void rejectedCredentialsAreReportedAsLoginFailure() throws Exception {
        generateFleet(1, 1);
        applyPreset("authFailure", "POST /oauth/token", null, null);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        Assertions.assertThrows(FailedLoginException.class, communicator::getMultipleStatistics);
    }
}
