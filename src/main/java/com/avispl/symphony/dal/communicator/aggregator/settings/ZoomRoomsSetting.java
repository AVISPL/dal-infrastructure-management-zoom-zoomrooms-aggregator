/*
 * Copyright (c) 2021 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.settings;

/**
 * Zoom Rooms account and rooms properties are formatted and normalized to fit PascalCase format.
 * When such control is triggered - adapter receives a formatted name, and it needs to receive the original value
 * in order of being able to send API request. This class contains all the data necessary.
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public enum ZoomRoomsSetting {
    /** Property for upcoming_meeting_alert meeting setting */
    UpcomingMeetingAlert("meeting:zoom_rooms:upcoming_meeting_alert"),
    /** Property for show_alert_before_meeting meeting setting */
    ShowAlertBeforeMeeting("meeting:zoom_rooms:show_alert_before_meeting"),
    /** Property for start_airplay_mirroring meeting setting */
    StartAirplayMirroring("meeting:zoom_rooms:start_airplay_mirroring"),
    /** Property for start_airplay_manually meeting setting */
    StartAirplayManually("meeting:zoom_rooms:start_airplay_manually"),
    /** Property for weekly_system_restart meeting setting */
    WeeklySystemRestart("meeting:zoom_rooms:weekly_system_restart"),
    /** Property for display_meeting_list meeting setting */
    DisplayMeetingList("meeting:zoom_rooms:display_meeting_list"),
    /** Property for display_top_banner meeting setting */
    DisplayTopBanner("meeting:zoom_rooms:display_top_banner"),
    /** Property for display_feedback_survey meeting setting */
    DisplayFeedbackSurvey("meeting:zoom_rooms:display_feedback_survey"),
    /** Property for auto_direct_sharing meeting setting */
    AutoDirectSharing("meeting:zoom_rooms:auto_direct_sharing"),
    /** Property for transform_meeting_to_private meeting setting */
    TransformMeetingToPrivate("meeting:zoom_rooms:transform_meeting_to_private"),
    /** Property for hide_id_for_private_meeting meeting setting */
    HideIDForPrivateMeeting("meeting:zoom_rooms:hide_id_for_private_meeting"),
    /** Property for auto_start_scheduled_meeting meeting setting */
    AutoStartScheduledMeeting("meeting:zoom_rooms:auto_start_scheduled_meeting"),
    /** Property for auto_stop_scheduled_meeting meeting setting */
    AutoStopScheduledMeeting("meeting:zoom_rooms:auto_stop_scheduled_meeting"),
    /** Property for audio_device_daily_auto_test meeting setting */
    AudioDeviceDailyAutoTest("meeting:zoom_rooms:audio_device_daily_auto_test"),
    /** Property for support_join_3rd_party_meeting meeting setting */
    SupportJoin3rdPartyMeeting("meeting:zoom_rooms:support_join_3rd_party_meeting"),
    /** Property for email_address_prompt_before_recording meeting setting */
    EmailAddressPromptBeforeRecording("meeting:zoom_rooms:email_address_prompt_before_recording"),
    /** Property for secure_connection_channel meeting setting */
    SecureConnectionChannel("meeting:zoom_rooms:secure_connection_channel"),
    /** Property for make_room_alternative_host meeting setting */
    MakeRoomAlternativeHost("meeting:zoom_rooms:make_room_alternative_host"),
    /** Property for encrypt_shared_screen_content meeting setting */
    EncryptSharedScreenContent("meeting:zoom_rooms:encrypt_shared_screen_content"),
    /** Property for allow_multiple_content_sharing meeting setting */
    AllowMultipleContentSharing("meeting:zoom_rooms:allow_multiple_content_sharing"),
    /** Property for show_non_video_participants meeting setting */
    ShowNonVideoParticipants("meeting:zoom_rooms:show_non_video_participants"),
    /** Property for show_call_history_in_room meeting setting */
    ShowCallHistoryInRoom("meeting:zoom_rooms:show_call_history_in_room"),
    /** Property for show_contact_list_on_controller meeting setting */
    ShowContactListOnController("meeting:zoom_rooms:show_contact_list_on_controller"),
    /** Property for count_attendees_number_in_room meeting setting */
    CountAttendeesNumberInRoom("meeting:zoom_rooms:count_attendees_number_in_room"),
    /** Property for send_whiteboard_to_internal_contact_only meeting setting */
    SendWhiteboardToInternalContactOnly("meeting:zoom_rooms:send_whiteboard_to_internal_contact_only"),

    /** Property for detect_microphone_error_alert meeting setting */
    DetectMicrophoneErrorAlert("alert:client_alert:detect_microphone_error_alert"),
    /** Property for detect_bluetooth_microphone_error_alert meeting setting */
    DetectBluetoothMicrohponeErrorAlert("alert:client_alert:detect_bluetooth_microphone_error_alert"),
    /** Property for detect_speaker_error_alert meeting setting */
    DetectSpeakerErrorAlert("alert:client_alert:detect_speaker_error_alert"),
    /** Property for detect_bluetooth_speaker_error_alert meeting setting */
    DetectBluetoothSpeakerErrorAlert("alert:client_alert:detect_bluetooth_speaker_error_alert"),
    /** Property for detect_camera_error_alert meeting setting */
    DetectCameraErrorAlert("alert:client_alert:detect_camera_error_alert"),
    /** Property for audio_not_meet_usability_threshold meeting setting */
    AudioNotMeetUsabilityThreshold("alert:notification:audio_not_meet_usability_threshold"),
    /** Property for audio_meet_usability_threshold meeting setting */
    AudioMeetUsabilityThreshold("alert:notification:audio_meet_usability_threshold"),
    /** Property for battery_low_and_not_charging meeting setting */
    BatteryLowAndNotCharging("alert:notification:battery_low_and_not_charging"),
    /** Property for battery_is_charging meeting setting */
    BatteryIsCharging("alert:notification:battery_is_charging"),
    /** Property for battery_percentage meeting setting */
    BatteryPercentage("alert:notification:battery_percentage"),
    /** Property for controller_scheduling_disconnected meeting setting */
    ControllerSchedulingDisconnected("alert:notification:controller_scheduling_disconnected"),
    /** Property for controller_scheduling_reconnected meeting setting */
    ControllerSchedulingReconnected("alert:notification:controller_scheduling_reconnected"),
    /** Property for cpu_usage_high_detected meeting setting */
    CpuUsageHighDetected("alert:notification:cpu_usage_high_detected"),
    /** Property for network_unstable_detected meeting setting */
    NetworkUnstableDetected("alert:notification:network_unstable_detected"),
    /** Property for zoom_room_offline meeting setting */
    ZoomRoomOffline("alert:notification:zoom_room_offline"),
    /** Property for zoom_room_come_back_online meeting setting */
    ZoomRoomComeBackOnline("alert:notification:zoom_room_come_back_online"),
    /** Property for sip_registration_failed meeting setting */
    SipRegistrationFailed("alert:notification:sip_registration_failed"),
    /** Property for sip_registration_re_enabled meeting setting */
    SipRegistrationReEnabled("alert:notification:sip_registration_re_enabled"),
    /** Property for mic_speaker_camera_disconnected meeting setting */
    MicSpeakerCameraDisconnected("alert:notification:mic_speaker_camera_disconnected"),
    /** Property for mic_speaker_camera_reconnected meeting setting */
    MicSpeakerCameraReconnected("alert:notification:mic_speaker_camera_reconnected"),
    /** Property for zoom_room_display_disconnected meeting setting */
    ZoomRoomDisplayDisconnected("alert:notification:zoom_room_display_disconnected");

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
