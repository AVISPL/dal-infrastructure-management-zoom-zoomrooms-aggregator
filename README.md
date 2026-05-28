# Zoom Rooms Integration – Capabilities & Configuration
This document covers Zoom Rooms Aggregator Capabilities and Configuration.

Symphony integrates with Zoom Rooms to provide comprehensive monitoring and control of Zoom Rooms environment. 
Main features are: real-time zoom room health monitoring, device and peripheral tracking, conference status, and settings management across Zoom Rooms environment (account settings and room settings).

## Main use cases
- **Monitor** zoom room health, device metrics, and meeting status
- **Track** individual device details — CPU, battery, connection, selected audio/video peripherals
- **Manage** Zoom Rooms settings at account-wide or per-room level
- **Inventory** keep zoom rooms and associated devices in check

## Prerequisites and where to start
Zoom Rooms Aggregator is communicating with the API on behalf of the Server-to-Server OAuth application, created on https://marketplace.zoom.us/

Required scopes are: 
- dashboard:read:zoomroom:admin -> View a meeting's metrics
- dashboard:read:zoomroom:admin -> View a Zoom Room metrics
- dashboard:read:list_zoomrooms:admin -> View Zoom Room metrics
- zoom_rooms:update:room_settings:admin -> Update a Zoom Room's settings
- zoom_rooms:update:account_settings:admin -> Update Zoom Room account settings
- zoom_rooms:read:room:admin -> View a Zoom Room
- zoom_rooms:read:room_settings:admin -> View a Zoom Room's settings
- zoom_rooms:read:account_settings:admin -> View Zoom Room account settings
- zoom_rooms:read:list_devices:admin -> View Zoom Room devices
- zoom_rooms:read:list_rooms:admin -> View Zoom Rooms
Some scopes can be ommitted, if the functionality is not being used by the aggregator (check the Configuration session for more details)

## Zoom Rooms Device Configuration and Provisioning
Once Server-to-Server OAuth application is created, use accountId, clientId and clientSecret for the Symphony device configuration. 
Zoom device model is associated with Zoom Rooms Aggregator on a catalog level:

| Type | Category | Manufacturer | Model | 
|---|---|---|---|
| Infrastructure | Management | Zoom | Zoom |

The aggregated (Zoom Room devices) will be available as aggregated devices with different models. 
Devices that have Zoom Rooms Aggregator device of Zoom model as Monitoring Proxy - are devices monitored by Zoom Rooms aggregator.
 
Once Zoom device is created with Monitoring Service -> Advanced Monitoring, http(s) management protocol must be selected.
Username: clientId
Password: clientSecret
Local Port: 443 or alternative, if re-routed with proxies
accountId Adapter Property must be set to the accountId value from Server-to-Server OAuth application.

When the device is configured, saved and set active, Zoom Rooms Aggregator will start communicating with the Zoom Rooms API to retrieve data about registered devices, based on the provided configuration. 
By default, the unprovisioned devices will appear on Aggregated Devices -> Unprovisioned Devices tab. 
Devices and available devices data can be tuned by adapter configuration properties 

| Property                                    | Description                                                                                                                                                                                                                                                   | Value                                                                        |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| deviceMetaDataRetrievalTimeout              | Timeout that defines the frequency of retrieving the general Zoom Rooms metadata                                                                                                                                                                              | 30000 by default, milliseconds                                               |
| roomDevicesRetrievalTimeout                 | Timeout that defines the frequency of retrieving the Zoom Rooms devices details                                                                                                                                                                               | 30000 by default, milliseconds                                               |
| roomSettingsRetrievalTimeout                | Timeout that defines the frequency of retrieving the Zoom Rooms settings                                                                                                                                                                                      | 30000 by default, milliseconds                                               |
| roomUserDetailsRetrievalTimeout             | Timeout that defines the frequency of retrieving the Zoom Rooms user details                                                                                                                                                                                  | 30000 by default, milliseconds                                               |
| metricsRetrievalTimeout                     | Timeout that defines the frequency of retrieving the Zoom Rooms metrics                                                                                                                                                                                       | 30000 by default, milliseconds                                               |
| liveMeetingDetailsRetrievalTimeout          | Timeout that defines the frequency of retrieving the Live Meeting details per Zoom Room instance. Ignored if displayLiveMeetingDetails property is set to false                                                                                               | 30000 by default, milliseconds                                               |
| liveMeetingDetailsDailyRequestRateThreshold | Zoom Dashboard API has daily limit, above which it is not possible to address it. This setting specifies the minimal number of requests left, below which live meeting details won't be provided in order to save request pool for Room Status data retrieval | 5000 by default                                                              |
| displayLiveMeetingDetails                   | Whether or not to display Zoom Rooms' live meeting details                                                                                                                                                                                                    | false by default                                                             |
| displayAccountSettings                      | Whether or not to display Account Settings controls on Aggregator level                                                                                                                                                                                       | true by default                                                              |
| displayRoomSettings                         | Whether or not to display Room Settings controls on Device level                                                                                                                                                                                              | true by default                                                              |
| zoomRoomLocations                           | CSV line of Locations (State, City, etc), to filter the Zoom Rooms by                                                                                                                                                                                         | Blank by default                                                             |
| zoomRoomTypes                               | CSV line of Zoom Room types, to filter the Zoom Rooms by                                                                                                                                                                                                      | Blank by default                                                             |
| accountId                                   | accountId for OAuth authentication                                                                                                                                                                                                                            |                                                                              |
| maxErrorLength                              | Maximum length of the errors listed in the Errors group in Zoom Rooms aggregator properties                                                                                                                                                                   | 120 by default                                                               |
| zoomOAuthHostname                           | Hostname to use for OAuth access token generation                                                                                                                                                                                                             | zoom.us by default. Should be provided without the protocol, port or slashes |
| includeRoomDevices                          | Include zoom room devices and report them as individual devices provisioned in symphony                                                                                                                                                                       | Boolean, false by default                                                    |
Note: When mentioned request thresholds are reached - this section of the API is not requested anymore. 

For detailed information on aggregator and its configuration, please refer to our knowledgebase -> https://symphony.knowledgeowl.com/help/zoom-rooms-technical-breakdown

## Available Monitored Data
Zoom Rooms Aggregator monitored data consists of 2 parts: Aggregator extended properties and Device extended properties. 
Aggregator properties is mostly service information - Adapter Metadata (AdapterBuildDate, AdapterUptime, AdapterVersion, LastMonitoringCycleDuration, MonitoredDevicesTotal, MonitoringCycleInterval), 
but also includes AccountAlertSettings and AccountMeetingSettings sections to adjust ZR settings in.

Aggregated Devices (including Zoom Rooms and Zoom Room Devices, includeRoomDevices property is enabled) provide the following monitoring and control capabilities: 

| Property Type | Description |
|---|---|
| Device metadata | Basic Zoom Room and Zoom Room Device information |
| Room Status | Status information of Zoom Room - bandwidth, controller battery status, controller connection, cpu, scheduling display battery status, peripherals status, PC connection and battery status |
| Room Devices | Room devices (computers/controllers/scheduling displays) summary -> device count, app version, online status |
| Alert and Meeting Settings | Zoom Rooms alert and meeting settings, overrides account level settings set on Aggregator level |
| Metrics | Only if metrics is enabled, since it relies on a limited Metrics API (https://developers.zoom.us/docs/api/rate-limits/) -> peripherals status, uptime, conference status, account details |

## Troubleshooting 
** Login Error **
- Check your Server-to-Server OAuth application configuration, make sure it's active match the requirements
- Make sure Symphony Zoom Rooms Aggregator Device is configured properly - http secure management interface, clientId/clientSecret/accountId are correct 

** API Error **
- Check the API error description
- If it mentions any configuration mismatches, make sure to provide proper values and configuration property data formats (API/OAuth domain, property groups names, timeout limits, etc.)

** Link Error/Ping Timeout ** 
- Make sure your Cloud Connector can reach the configured zoom api hostname
- Check Ping Protocol in Symphony Zoom Rooms Aggregator Device configuration
- Try switching between ICMP/TCP modes, since certain protocols may be unavailable on remote and/or blocked by your proxy settings

If none of the recommended steps help, please enter an SOS ticket at {https://avi-spl.atlassian.net/servicedesk/customer/portals}

## What Melody can do with it: 
- Find Zoom Rooms Aggregated Devices (Zoom Aggregator as Monitoring Proxy)
- Verify Zoom Rooms Aggregator configuration.

Primarily, all devices that have device of type [Infrastructure|Management|Zoom|Zoom] set as Monitoring Proxy, are target Zoom Room devices.

## What Melody cannot do with it: 
- Provision the devices 
