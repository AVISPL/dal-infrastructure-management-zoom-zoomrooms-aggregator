/*
 * Copyright (c) 2021 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator;

/**
 * Zoom Rooms account and rooms properties are formatted and normalized to fit PascalCase format.
 * When such control is triggered - adapter receives a formatted name, and it needs to receive the original value
 * in order of being able to send API request. This class contains all the data necessary.
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public enum ZoomRoomsSetting {
    UpcomingMeetingAlert("upcoming_meeting_alert"),
    ShowAlertBeforeMeeting("show_alert_before_meeting"),
    StartAirplayMirroring("start_airplay_mirroring"),
    StartAirplayManually("start_airplay_manually"),
    WeeklySystemRestart("weekly_system_restart"),
    DisplayMeetingList("display_meeting_list"),
    DisplayTopBanner("display_top_banner"),
    DisplayFeedbackSurvey("display_feedback_survey"),
    AutoDirectSharing("auto_direct_sharing"),
    TransformMeetingToPrivate("transform_meeting_to_private"),
    HideIDForPrivateMeeting("hide_id_for_private_meeting"),
    AutoStartScheduledMeeting("auto_start_scheduled_meeting"),
    AutoStopScheduledMeeting("auto_stop_scheduled_meeting"),
    AudioDeviceDailyAutoTest("audio_device_daily_auto_test"),
    SupportJoin3rdPartyMeeting("support_join_3rd_party_meeting"),
    SecureConnectionChannel("secure_connection_channel"),
    MakeRoomAlternativeHost("make_room_alternative_host"),
    EncryptSharedScreenContent("encrypt_shared_screen_content"),
    AllowMultipleContentSharing("allow_multiple_content_sharing"),
    ShowNonVideoParticipants("show_non_video_participants"),
    ShowCallHistoryInRoom("show_call_history_in_room"),
    ShowContactListOnController("show_contact_list_on_controller"),
    CountAttendeesNumberInRoom("count_attendees_number_in_room"),
    SendWhiteboardToInternalContactOnly("send_whiteboard_to_internal_contact_only");

    /* Stored name of the property, to use for control commands */
    private final String name;

    private ZoomRoomsSetting(String s) {
        name = s;
    }

    /**
     * Check if the name is equal to the parameter value
     *
     * @param externalName name to compare current name with
     * @return boolean value, indicating whether values are equal or not
     */
    public boolean equalsName(String externalName) {
        // (externalName == null) check is not needed because name.equals(null) returns false
        return name.equals(externalName);
    }

    /**
     * String value of the current ZoomRoomsSetting.
     *
     * @return name of the setting.
     */
    public String toString() {
        return this.name;
    }
}
