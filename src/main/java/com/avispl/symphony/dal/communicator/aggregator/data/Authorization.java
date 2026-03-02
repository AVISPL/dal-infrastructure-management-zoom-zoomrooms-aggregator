package com.avispl.symphony.dal.communicator.aggregator.data;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Authorization {
    private final long loginDateTime;
    /**
     * Create an instance of LoginInfo
     */
    public Authorization() {
        this.loginDateTime = 0;
    }

    @JsonProperty("expires_in")
    private Integer expiresIn;
    @JsonProperty("access_token")
    private String accessToken;

    /**
     * Retrieves {@link #expiresIn}
     *
     * @return value of {@link #expiresIn}
     */
    public Integer getExpiresIn() {
        return expiresIn;
    }

    /**
     * Sets {@link #expiresIn} value
     *
     * @param expiresIn new value of {@link #expiresIn}
     */
    public void setExpiresIn(Integer expiresIn) {
        this.expiresIn = expiresIn;
    }

    /**
     * Retrieves {@link #accessToken}
     *
     * @return value of {@link #accessToken}
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Sets {@link #accessToken} value
     *
     * @param accessToken new value of {@link #accessToken}
     */
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }
    /**
     * Check token expiry time
     * Token must be refreshed when half of the expiresIn time has elapsed.
     *
     * @return boolean
     */
    public boolean updateRequired() {
        long elapsed = (System.currentTimeMillis() - loginDateTime) / 1000;
        return elapsed >= expiresIn/2;
    }
}
