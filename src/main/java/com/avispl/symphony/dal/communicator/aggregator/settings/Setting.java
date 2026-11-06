/*
 * Copyright (c) 2021 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Setting class takes value of {@link ZoomRoomsSetting} and splits data onto settingType, parentNode of the property
 * and settingName to use.
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public class Setting {
    private String settingType;
    private String parentNode;
    private String settingName;
    /** Former names of the setting, written along with {@link #settingName} for accounts that still use them */
    private List<String> legacyNames = Collections.emptyList();

    /**
     * Colon separated string that looks like this
     * meeting:zoom_rooms:upcoming_meeting_alert
     * which corresponds to settingType:parentNode:settingName, optionally followed by :legacyName entries
     * <p>
     * It is necessary to keep track of this information, for having an easy support for the room/account
     * settings controllable properties.
     *
     * @param settingString of a settingType:parentNode:settingName format
     * @return Setting instance with all the data populated
     */
    public static Setting fromString(String settingString) {
        String[] values = settingString.split(":");
        if (values.length < 3) {
            return null;
        }
        Setting setting = new Setting();
        setting.setSettingType(values[0]);
        setting.setParentNode(values[1]);
        setting.setSettingName(values[2]);
        setting.legacyNames = Arrays.asList(values).subList(3, values.length);
        return setting;
    }

    /**
     * Retrieves {@code {@link #settingType}}
     *
     * @return value of {@link #settingType}
     */
    public String getSettingType() {
        return settingType;
    }

    /**
     * Sets {@code settingType}
     *
     * @param settingType the {@code java.lang.String} field
     */
    public void setSettingType(String settingType) {
        this.settingType = settingType;
    }

    /**
     * Retrieves {@code {@link #parentNode}}
     *
     * @return value of {@link #parentNode}
     */
    public String getParentNode() {
        return parentNode;
    }

    /**
     * Sets {@code parentNode}
     *
     * @param parentNode the {@code java.lang.String} field
     */
    public void setParentNode(String parentNode) {
        this.parentNode = parentNode;
    }

    /**
     * Retrieves {@code {@link #settingName}}
     *
     * @return value of {@link #settingName}
     */
    public String getSettingName() {
        return settingName;
    }

    /**
     * Sets {@code settingName}
     *
     * @param settingName the {@code java.lang.String} field
     */
    public void setSettingName(String settingName) {
        this.settingName = settingName;
    }

    /**
     * Retrieves {@link #legacyNames}
     *
     * @return value of {@link #legacyNames}
     */
    public List<String> getLegacyNames() {
        return legacyNames;
    }
}
