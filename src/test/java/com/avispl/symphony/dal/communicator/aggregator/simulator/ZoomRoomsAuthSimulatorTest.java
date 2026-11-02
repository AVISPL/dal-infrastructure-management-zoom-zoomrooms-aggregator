/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import javax.security.auth.login.FailedLoginException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.aggregator.ZoomRoomsAggregatorCommunicator;

/**
 * Authentication: rejected token requests, expired / revoked tokens. Server-to-Server OAuth (README Authentication).
 */
public class ZoomRoomsAuthSimulatorTest extends ZoomRoomsSimulatorTestBase {

    private static final String TOKEN = "POST /oauth/token";

    /**
     * authFailure on the token endpoint for an established adapter: a login error, then the adapter authenticates and
     * recovers once the endpoint accepts the credentials again. The adapter reuses a valid token, so a short token
     * lifetime makes it request a new one; while the rule is active every token request is rejected, so the call that
     * needs the new token reports the login error whichever thread asked first.
     */
    @Test
    public void tokenRejectedOnceRecoversOnTheNextCycle() throws Exception {
        generateFleet(2, 2);
        patchState("{\"auth\": {\"tokenLifetimeSec\": 4}}");
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        List<AggregatedDevice> established = waitForFullCollection(communicator);
        patchState("{\"auth\": {\"tokenLifetimeSec\": 3600}}");
        String ruleId = applyPreset("authFailure", TOKEN, "ALWAYS", null);
        // the adapter renews a token once half of expires_in (3 s) has elapsed
        Thread.sleep(2_000);

        Exception first = null;
        try {
            communicator.getMultipleStatistics();
        } catch (Exception e) {
            first = e;
        }
        Assertions.assertTrue(rule(ruleId).path("fired").asInt() >= 1, "the rejected token request happened");
        Assertions.assertTrue(first instanceof FailedLoginException, "rejected credentials reported as a login error: " + first);
        removeRule(ruleId);

        ExtendedStatistics recovered = aggregatorStatistics(communicator);
        Assertions.assertFalse(recovered.getStatistics().containsKey("Errors#LoginError"), "login error cleared: " + recovered.getStatistics());
        List<AggregatedDevice> devices = pollUntil(communicator, "devices after recovery", ONE_INTERVAL_WAIT_MS,
                list -> list.size() == established.size());
        Assertions.assertEquals(established.size(), devices.size());
    }

    /**
     * authFailure ALWAYS: a login error on every call, no retry loop. Token requests are counted in the request log:
     * at most one per adapter call plus the background collector's one attempt per rooms-list period, and none while
     * nobody calls the adapter.
     */
    @Test
    public void tokenAlwaysRejectedReportsLoginErrorWithoutRetryLoop() throws Exception {
        generateFleet(2, 2);
        applyPreset("authFailure", TOKEN, "ALWAYS", null);
        Instant since = Instant.now();
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();

        int calls = 0;
        for (int i = 0; i < 5; i++) {
            calls++;
            Assertions.assertThrows(FailedLoginException.class, communicator::getMultipleStatistics, "call " + calls);
            calls++;
            Exception devicesError = null;
            try {
                communicator.retrieveMultipleStatistics();
            } catch (Exception e) {
                devicesError = e;
            }
            Assertions.assertNotNull(devicesError, "retrieveMultipleStatistics reports the login error");
            Thread.sleep(POLL_PERIOD_MS);
        }
        long activeSeconds = (System.currentTimeMillis() - since.toEpochMilli()) / 1000;
        int duringCalls = requestsSince(since, target -> target.startsWith(TOKEN)).size();
        int backgroundAllowance = (int) (activeSeconds * 1000 / COLLECTION_INTERVAL_MS) + 1;
        Assertions.assertTrue(duringCalls <= calls + backgroundAllowance,
                duringCalls + " token requests for " + calls + " adapter calls in " + activeSeconds + " s (retry loop?)");

        Instant idleSince = Instant.now();
        Thread.sleep(15_000);
        int whileIdle = requestsSince(idleSince, target -> target.startsWith(TOKEN)).size();
        Assertions.assertTrue(whileIdle <= 1, whileIdle + " token requests in 15 s without adapter calls (retry loop)");
    }

    /**
     * sessionExpired COUNT 1 on the rooms list: the adapter re-authenticates once, retries, and the cycle succeeds.
     */
    @Test
    public void expiredSessionOnceReauthenticatesAndTheCycleSucceeds() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        final int expected = waitForFullCollection(communicator).size();
        String ruleId = applyPreset("sessionExpired", "GET /v2/rooms", "COUNT", 1);
        Instant since = Instant.now();

        final List<String> polls = new ArrayList<>();
        waitUntil(communicator, "the next rooms list request", ONE_INTERVAL_WAIT_MS, () -> rule(ruleId).path("fired").asInt() == 1);
        pollFor(communicator, 6000, list -> polls.add(list == null ? "threw" : String.valueOf(list.size())));

        List<JsonNode> log = requestsSince(since, target -> target.startsWith("GET /v2/rooms?") || target.startsWith(TOKEN));
        int unauthorized = -1;
        for (int i = 0; i < log.size(); i++) {
            if ("401".equals(log.get(i).path("status").asText())) {
                unauthorized = i;
                break;
            }
        }
        Assertions.assertTrue(unauthorized >= 0, "the 401 is in the request log: " + log);
        boolean reauthenticated = false;
        boolean retried = false;
        for (int i = unauthorized + 1; i < log.size(); i++) {
            String target = log.get(i).path("target").asText();
            String status = log.get(i).path("status").asText();
            reauthenticated |= target.startsWith(TOKEN) && "200".equals(status);
            if (reauthenticated && target.startsWith("GET /v2/rooms?") && "200".equals(status)) {
                retried = true;
                break;
            }
        }
        Assertions.assertTrue(reauthenticated && retried, "token requested and the rooms list retried after the 401: " + log);
        ExtendedStatistics statistics = aggregatorStatistics(communicator);
        Assertions.assertTrue(statistics.getStatistics().keySet().stream().noneMatch(key -> key.startsWith("Errors#")), statistics.getStatistics().toString());
        for (String poll : polls) {
            Assertions.assertEquals(String.valueOf(expected), poll, "device list intact across the re-authentication: " + polls);
        }
    }

    /**
     * Short token lifetime (auth.tokenLifetimeSec): the token expires before the next background cycle. Nobody calls
     * the adapter meanwhile (every adapter call requests a new token, which would hide the expiry), so the cycle meets
     * 401s; the adapter re-authenticates once and retries, and collection continues.
     */
    @Test
    public void expiredTokenInTheBackgroundCycleIsRenewedAndRetried() throws Exception {
        generateFleet(2, 2);
        patchState("{\"auth\": {\"tokenLifetimeSec\": 5}}");
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        int expected = waitForFullCollection(communicator).size();
        assertBackgroundCycleRecoversFromUnauthorized(communicator, expected);
    }

    /** Tokens revoked on the simulator: the next background cycle meets 401s, re-authenticates, retries, recovers. */
    @Test
    public void revokedTokenInTheBackgroundCycleIsRenewedAndRetried() throws Exception {
        generateFleet(2, 2);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        int expected = waitForFullCollection(communicator).size();
        admin(HttpMethod.POST, "/tokens/revoke", null, 204);
        assertBackgroundCycleRecoversFromUnauthorized(communicator, expected);
    }

    /**
     * A valid token (3600 s) is reused: the adapter must not request a token on every call. Expected at most one token
     * request while 10 adapter calls run within a minute.
     */
    @Test
    public void validTokenIsReusedAcrossPolls() throws Exception {
        generateFleet(1, 1);
        ZoomRoomsAggregatorCommunicator communicator = newCommunicator();
        waitForFullCollection(communicator);
        Instant since = Instant.now();
        for (int i = 0; i < 5; i++) {
            communicator.getMultipleStatistics();
            communicator.retrieveMultipleStatistics();
            Thread.sleep(POLL_PERIOD_MS);
        }
        List<JsonNode> tokenRequests = requestsSince(since, target -> target.startsWith(TOKEN));
        Assertions.assertTrue(tokenRequests.size() <= 1, tokenRequests.size() + " token requests for 10 adapter calls with a token valid for 3600 s");
    }

    /**
     * Leaves the adapter alone until its background cycle has run with the invalid token (the cycle starts a monitoring
     * rate, 1 min, after the last one when nobody polls), then checks: 401s were met, a token was requested after the
     * first one, every 401 was retried successfully within 10 s, and after polls resume there are no error statistics
     * and the device list is intact on every poll.
     */
    private void assertBackgroundCycleRecoversFromUnauthorized(ZoomRoomsAggregatorCommunicator communicator, int expected) throws Exception {
        Instant since = Instant.now();
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && requestsSince(since, target -> target.startsWith("GET /v2/")).stream()
                .noneMatch(r -> "401".equals(r.path("status").asText()))) {
            Thread.sleep(POLL_PERIOD_MS);
        }
        Thread.sleep(10_000);
        List<JsonNode> log = requestsSince(since, target -> target.startsWith("GET /v2/") || target.startsWith(TOKEN));
        Instant firstUnauthorized = null;
        List<String> notRetried = new ArrayList<>();
        int unauthorized = 0;
        for (int i = 0; i < log.size(); i++) {
            if (!"401".equals(log.get(i).path("status").asText())) {
                continue;
            }
            unauthorized++;
            Instant at = Instant.parse(log.get(i).path("time").asText());
            if (firstUnauthorized == null) {
                firstUnauthorized = at;
            }
            String target = log.get(i).path("target").asText();
            boolean retried = false;
            for (int j = i + 1; j < log.size() && !retried; j++) {
                retried = target.equals(log.get(j).path("target").asText()) && "200".equals(log.get(j).path("status").asText())
                        && Instant.parse(log.get(j).path("time").asText()).isBefore(at.plusSeconds(10));
            }
            if (!retried) {
                notRetried.add(log.get(i).path("time").asText() + " " + target);
            }
        }
        System.out.println("[simulatorTest] background cycle with an invalid token: " + unauthorized + " x 401, not retried: " + notRetried.size());
        Assertions.assertNotNull(firstUnauthorized, "the background cycle never met a 401 within 120 s: " + log.size() + " requests");
        final Instant first = firstUnauthorized;
        Assertions.assertTrue(log.stream().anyMatch(r -> r.path("target").asText().startsWith(TOKEN) && "200".equals(r.path("status").asText())
                && !Instant.parse(r.path("time").asText()).isBefore(first)), "token requested after the first 401");

        final List<String> polls = new ArrayList<>();
        pollFor(communicator, 6000, list -> polls.add(list == null ? "threw" : String.valueOf(list.size())));
        ExtendedStatistics statistics = aggregatorStatistics(communicator);
        for (String poll : polls) {
            Assertions.assertEquals(String.valueOf(expected), poll, "device list intact on every poll: " + polls);
        }
        Assertions.assertTrue(notRetried.isEmpty(), notRetried.size() + " of " + unauthorized + " requests answered 401 were not retried"
                + " (re-authenticate once under a lock and retry, dal-conventions 8):\n  " + String.join("\n  ", notRetried));
        Assertions.assertTrue(errors(statistics).isEmpty(), "error statistics after the recovery: " + errors(statistics));
    }

    private static List<String> errors(ExtendedStatistics statistics) {
        List<String> result = new ArrayList<>();
        statistics.getStatistics().forEach((key, value) -> {
            if (key.startsWith("Errors#")) {
                result.add(key + "=" + value);
            }
        });
        return result;
    }
}
