package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.error.ResourceNotReachableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Error handling tests that run without Zoom API access: HTTP calls are stubbed by overriding doGet/doPost.
 *
 * SYAL-4000
 */
@Tag("Mock")
public class ZoomRoomsAggregatorErrorHandlingTest {

    /**
     * Account settings endpoint answers the first request per setting type, and fails every request after it.
     * Before the fix, getMultipleStatistics requested each setting type twice and passed the second (null) response
     * to the property mapper, failing with SpelEvaluationException: EL1011E.
     */
    @Test
    public void accountSettingsAreRequestedOncePerTypeAndMapped() throws Exception {
        Map<String, AtomicInteger> settingsRequests = new ConcurrentHashMap<>();
        ZoomRoomsAggregatorCommunicator communicator = new StubbedCommunicator() {
            @Override
            protected <Response> Response doGet(String url, Class<Response> responseClass) throws Exception {
                if (url.contains("rooms/account_settings")) {
                    String settingType = url.substring(url.indexOf("setting_type=") + "setting_type=".length());
                    if (settingsRequests.computeIfAbsent(settingType, k -> new AtomicInteger()).incrementAndGet() > 1) {
                        throw new CommandFailureException("api.zoom.us", url, "Simulated failure", 500);
                    }
                    return responseClass.cast(settingsResponse(settingType));
                }
                throw new IllegalStateException("Unexpected request: " + url);
            }
        };
        communicator.setDisplayAccountSettings(true);
        communicator.init();
        try {
            ExtendedStatistics statistics = (ExtendedStatistics) communicator.getMultipleStatistics().get(0);

            Assertions.assertEquals(1, settingsRequests.get("meeting").get());
            Assertions.assertEquals(1, settingsRequests.get("alert").get());
            Assertions.assertEquals("true", controlValue(statistics, "AccountMeetingSettings#UpcomingMeetingAlert"));
            Assertions.assertEquals("true", controlValue(statistics, "AccountAlertSettings#DetectCameraErrorAlert"));
        } finally {
            communicator.destroy();
        }
    }

    /**
     * Account settings endpoint fails: statistics are still reported, without the account settings groups.
     */
    @Test
    public void failedAccountSettingsDoNotBreakStatistics() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new StubbedCommunicator() {
            @Override
            protected <Response> Response doGet(String url, Class<Response> responseClass) throws Exception {
                throw new CommandFailureException("api.zoom.us", url, "Simulated failure", 500);
            }
        };
        communicator.setDisplayAccountSettings(true);
        communicator.init();
        try {
            ExtendedStatistics statistics = (ExtendedStatistics) communicator.getMultipleStatistics().get(0);

            Assertions.assertTrue(statistics.getStatistics().keySet().stream().noneMatch(key -> key.startsWith("AccountMeetingSettings#")));
            Assertions.assertTrue(statistics.getStatistics().keySet().stream().noneMatch(key -> key.startsWith("AccountAlertSettings#")));
        } finally {
            communicator.destroy();
        }
    }

    /**
     * OAuth endpoint is unreachable: reported as a connectivity error, not as a login failure.
     */
    @Test
    public void unreachableOAuthEndpointIsNotReportedAsLoginFailure() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new OAuthFailingCommunicator(
                new ResourceNotReachableException("Cannot reach resource at https://zoom.us/oauth/token"));
        communicator.init();
        try {
            Assertions.assertThrows(ResourceNotReachableException.class, communicator::getMultipleStatistics);
        } finally {
            communicator.destroy();
        }
    }

    /**
     * OAuth endpoint rejects the credentials: still reported as a login failure.
     */
    @Test
    public void rejectedOAuthCredentialsAreReportedAsLoginFailure() throws Exception {
        ZoomRoomsAggregatorCommunicator communicator = new OAuthFailingCommunicator(
                new CommandFailureException("zoom.us", "oauth/token", "Unauthorized", 401));
        communicator.init();
        try {
            Assertions.assertThrows(FailedLoginException.class, communicator::getMultipleStatistics);
        } finally {
            communicator.destroy();
        }
    }

    private static String controlValue(ExtendedStatistics statistics, String name) {
        return statistics.getControllableProperties().stream()
                .filter(control -> name.equals(control.getName()))
                .map(control -> String.valueOf(control.getValue()))
                .findFirst()
                .orElse(null);
    }

    private static JsonNode settingsResponse(String settingType) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        if ("meeting".equals(settingType)) {
            return objectMapper.readTree("{\"zoom_rooms\": {\"upcoming_meeting_alert\": true}}");
        }
        return objectMapper.readTree("{\"client_alert\": {\"detect_camera_error_alert\": true}}");
    }

    /**
     * Communicator with configured credentials and a successful OAuth token request.
     */
    private static class StubbedCommunicator extends ZoomRoomsAggregatorCommunicator {
        StubbedCommunicator() throws IOException {
            setLogin("clientId");
            setPassword("clientSecret");
            setAccountId("accountId");
            setHost("api.zoom.us");
            setProtocol("https");
            setPort(443);
        }

        @Override
        protected <Request, Response> Response doPost(String url, Request request, Class<Response> responseClass) throws Exception {
            return new ObjectMapper().readValue("{\"access_token\": \"token\", \"expires_in\": 3599}", responseClass);
        }
    }

    /**
     * Communicator whose OAuth token request fails with the given exception.
     */
    private static class OAuthFailingCommunicator extends StubbedCommunicator {
        private final Exception oauthError;

        OAuthFailingCommunicator(Exception oauthError) throws IOException {
            this.oauthError = oauthError;
        }

        @Override
        protected <Request, Response> Response doPost(String url, Request request, Class<Response> responseClass) throws Exception {
            throw oauthError;
        }
    }
}
