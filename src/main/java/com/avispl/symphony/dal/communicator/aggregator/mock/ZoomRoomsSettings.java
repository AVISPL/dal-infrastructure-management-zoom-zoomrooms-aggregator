package com.avispl.symphony.dal.communicator.aggregator.mock;

public enum ZoomRoomsSettings {
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

    private final String name;

    private ZoomRoomsSettings(String s) {
        name = s;
    }

    public boolean equalsName(String otherName) {
        // (otherName == null) check is not needed because name.equals(null) returns false
        return name.equals(otherName);
    }

    public String toString() {
        return this.name;
    }
}
