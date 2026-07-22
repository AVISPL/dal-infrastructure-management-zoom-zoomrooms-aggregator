package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.util.List;

@Tag("test")
public class ZoomRoomsAggregatorCommunicatorTest {
    static ZoomRoomsAggregatorCommunicator mockAggregatorCommunicator;

    @BeforeEach
    public void init() throws Exception {
        mockAggregatorCommunicator = new ZoomRoomsAggregatorCommunicator();
        mockAggregatorCommunicator.setLogin("");
        mockAggregatorCommunicator.setPassword("");
        mockAggregatorCommunicator.setHost("");
        mockAggregatorCommunicator.setProtocol("");
        mockAggregatorCommunicator.setPort(443);
        mockAggregatorCommunicator.setIncludeRoomDevices(true);
        mockAggregatorCommunicator.setAccountId("");
    }

    @Test
    public void deviceCatalogMappingTest() throws Exception {
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        List<AggregatedDevice> devices = mockAggregatorCommunicator.retrieveMultipleStatistics();

        Assert.assertFalse("No devices returned — background thread may not have completed", devices.isEmpty());

        for (AggregatedDevice device : devices) {
            // Only child room devices carry the "ZoomRoomId" property.
            // Parent Zoom Rooms (built from model-mapping.yml) do not have it.
            boolean isRoomDevice = device.getProperties() != null
                    && device.getProperties().containsKey("ZoomRoomId");

            if (!isRoomDevice) {
                // Parent rooms: model-mapping.yml Generic model already sets these — verify untouched
                Assert.assertEquals("Parent room type should be Computer: " + device.getDeviceName(),
                        "Computer", device.getType());
                Assert.assertEquals("Parent room category should be Zoom Rooms: " + device.getDeviceName(),
                        "Zoom Rooms", device.getCategory());
                continue;
            }

            String rawType = device.getProperties().get("DeviceType");
            System.out.printf("Room device: %-50s | raw=%-30s | type=%-15s | category=%-20s | make=%s%n",
                    device.getDeviceName(), rawType, device.getType(), device.getCategory(), device.getDeviceMake());

            if ("Zoom Rooms Computer".equals(rawType)) {
                Assert.assertEquals("Zoom Rooms Computer → type", "Computer", device.getType());
                Assert.assertEquals("Zoom Rooms Computer → category", "Zoom Rooms", device.getCategory());

            } else if ("Controller".equals(rawType)) {
                Assert.assertEquals("AV Controllers → type", "AV Devices", device.getType());
                Assert.assertEquals("AV Controllers → category", "AV Controllers", device.getCategory());

            } else if ("Scheduling Display".equals(rawType)) {
                String make = device.getDeviceMake();
                if (make != null && make.startsWith("Crestron")) {
                    Assert.assertEquals("Crestron Scheduling Display → type", "AV Devices", device.getType());
                    Assert.assertEquals("Crestron Scheduling Display → category", "Touch Screens", device.getCategory());
                    Assert.assertEquals("Crestron Scheduling Display → make", "Crestron", device.getDeviceMake());
                }

            } else {
                // Any unmapped type: default branch in applyDeviceCatalogMapping sets category = rawType
                Assert.assertEquals("Unmapped device type → category should equal raw device_type: " + device.getDeviceName(),
                        rawType, device.getCategory());
            }
        }
    }

    @Test
    public void getDevicesWithFilteringTest() throws Exception {
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.setZoomRoomTypes("ZoomRoom, SchedulingDisplayOnly, DigitalSignageOnly");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(18, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomTypes(" DigitalSignageOnly");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertTrue(devices.isEmpty());

        mockAggregatorCommunicator.setZoomRoomTypes("");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(18, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations("SomeLocationThatNoneOfTheDevicesHave");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertTrue(devices.isEmpty());

        mockAggregatorCommunicator.setZoomRoomLocations("");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(18, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }

    @Test
    public void getDevicesWithFilteringTestOAuth() throws Exception {
        mockAggregatorCommunicator.setLogin("");
        mockAggregatorCommunicator.setPassword("qnZlYQtMSWDahoCjsU4QKrarGO7cFBLs");

        mockAggregatorCommunicator.setDisplayAccountSettings(true);
        mockAggregatorCommunicator.setDisplayLiveMeetingDetails(true);
        mockAggregatorCommunicator.setDisplayRoomSettings(true);
        mockAggregatorCommunicator.setAccountId("");
        mockAggregatorCommunicator.setIncludeRoomDevices(true);
//        mockAggregatorCommunicator.setZoomRoomTypes("ZoomRoom, SchedulingDisplayOnly, DigitalSignageOnly");
//        mockAggregatorCommunicator.setZoomRoomLocations("Chicago");

        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(500000);
        List<AggregatedDevice> devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        mockAggregatorCommunicator.getMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(46, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations("");
        mockAggregatorCommunicator.setZoomRoomTypes(" DigitalSignageOnly");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertTrue(devices.isEmpty());

        mockAggregatorCommunicator.setZoomRoomTypes("");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(26, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations("SomeLocationThatNoneOfTheDevicesHave");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertTrue(devices.isEmpty());

        mockAggregatorCommunicator.setZoomRoomLocations("");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(26, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }

    @Test
    public void getDevicesWithLocationsFilteringTest() throws Exception {
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.setDisplayRoomSettings(true);
        mockAggregatorCommunicator.setZoomRoomLocations("Chicago,Arizona");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(6, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations("Chicago");
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(4, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations(null);
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(18, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }

    @Test
    public void getDevicesWithDelayTest() throws Exception {
        mockAggregatorCommunicator.setLogin("");
        mockAggregatorCommunicator.setPassword("");
        mockAggregatorCommunicator.setAccountId("");
        //mockAggregatorCommunicator.setExcludePropertyGroups("RoomUserDetails,RoomControlSettings,RoomDevices");
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.setMetricsRetrievalTimeout(90000);
        mockAggregatorCommunicator.setDeviceMetaDataRetrievalTimeout(60000);
        mockAggregatorCommunicator.setRoomDevicesRetrievalTimeout(60000);
        mockAggregatorCommunicator.setRoomSettingsRetrievalTimeout(30000);
        mockAggregatorCommunicator.setRoomUserDetailsRetrievalTimeout(60000);
        mockAggregatorCommunicator.setDisplayLiveMeetingDetails(true);
        mockAggregatorCommunicator.setDisplayRoomSettings(true);
        mockAggregatorCommunicator.setIncludeRoomDevices(true);
        mockAggregatorCommunicator.setIncludeRoomDevicesInCalls(true);
        List<AggregatedDevice> devices;
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(18, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }

    @Test
    public void getDevicesWithDelayTestJnJ() throws Exception {
        mockAggregatorCommunicator.setLogin("");
        mockAggregatorCommunicator.setPassword("");
        mockAggregatorCommunicator.setAccountId("");
        //mockAggregatorCommunicator.setExcludePropertyGroups("RoomUserDetails,RoomControlSettings,RoomDevices");
        mockAggregatorCommunicator.init();
//        mockAggregatorCommunicator.setMetricsRetrievalTimeout(30000);
//        mockAggregatorCommunicator.setDeviceMetaDataRetrievalTimeout(30000);
//        mockAggregatorCommunicator.setRoomDevicesRetrievalTimeout(30000);
//        mockAggregatorCommunicator.setRoomSettingsRetrievalTimeout(30000);
//        mockAggregatorCommunicator.setRoomUserDetailsRetrievalTimeout(60000);
//        mockAggregatorCommunicator.setDisplayLiveMeetingDetails(true);
//        mockAggregatorCommunicator.setDisplayRoomSettings(true);
//        mockAggregatorCommunicator.setIncludeRoomDevices(true);
//        mockAggregatorCommunicator.setIncludeRoomDevicesInCalls(true);
        List<AggregatedDevice> devices;
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        List<Statistics> statistics = mockAggregatorCommunicator.getMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(18, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }

    @Test
    public void pingTest() throws Exception {
        mockAggregatorCommunicator.init();
        int pingLatency = mockAggregatorCommunicator.ping();
        Assert.assertNotEquals(0, pingLatency);
        System.out.println("Ping latency calculated: " + pingLatency);
    }

    @Test
    public void getAggregatorDataTest() throws Exception {
        mockAggregatorCommunicator.init();
        List<Statistics> statistics = mockAggregatorCommunicator.getMultipleStatistics();
        Assert.assertEquals(1, statistics.size());
        Assert.assertNotNull(statistics.get(0));
    }

    @Test
    public void controlRoomSettingTest() throws Exception {
        mockAggregatorCommunicator.init();
        String roomId = "";
        String property = "RoomMeetingSettings#UpcomingMeetingAlert";
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty(property);
        controllableProperty.setValue(0);
        controllableProperty.setDeviceId(roomId);

        AdvancedControllableProperty startControl = mockAggregatorCommunicator.retrieveMultipleStatistics().stream().filter(aggregatedDevice ->
                aggregatedDevice.getDeviceId().equals(roomId)).findFirst().get()
                .getControllableProperties().stream().filter(advancedControllableProperty ->
                        advancedControllableProperty.getName().equals(property)).findFirst().get();

        mockAggregatorCommunicator.controlProperty(controllableProperty);

        AdvancedControllableProperty endControl = mockAggregatorCommunicator.retrieveMultipleStatistics().stream().filter(aggregatedDevice ->
                aggregatedDevice.getDeviceId().equals(roomId)).findFirst().get()
                .getControllableProperties().stream().filter(advancedControllableProperty ->
                        advancedControllableProperty.getName().equals(property)).findFirst().get();

        Assert.assertFalse((Boolean.parseBoolean(String.valueOf(endControl.getValue()))));
    }

    @Test
    public void controlNumericRoomSettingTest() throws Exception {
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);

        int value = 20;
        String roomId = "kjG6xV4jScasP0oDvBwSRA";
        String property = "RoomControlsAlertSettings#BatteryPercentage";
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty(property);
        controllableProperty.setValue(value);
        controllableProperty.setDeviceId(roomId);

        AdvancedControllableProperty startControl = mockAggregatorCommunicator.retrieveMultipleStatistics().stream().filter(aggregatedDevice ->
                aggregatedDevice.getDeviceId().equals(roomId)).findFirst().get()
                .getControllableProperties().stream().filter(advancedControllableProperty ->
                        advancedControllableProperty.getName().equals(property)).findFirst().get();

        mockAggregatorCommunicator.controlProperty(controllableProperty);

        Thread.sleep(60000);

        AdvancedControllableProperty endControl = mockAggregatorCommunicator.retrieveMultipleStatistics().stream().filter(aggregatedDevice ->
                aggregatedDevice.getDeviceId().equals(roomId)).findFirst().get()
                .getControllableProperties().stream().filter(advancedControllableProperty ->
                        advancedControllableProperty.getName().equals(property)).findFirst().get();

        Assert.assertEquals(value, endControl.getValue());
    }

    @Test
    public void testDestructionWithoutCorrectInstantiation() throws Exception {
        mockAggregatorCommunicator.setLogin("");
        mockAggregatorCommunicator.setPassword("");

        mockAggregatorCommunicator.setDisplayAccountSettings(true);
        mockAggregatorCommunicator.setDisplayLiveMeetingDetails(true);
        mockAggregatorCommunicator.setDisplayRoomSettings(true);
        mockAggregatorCommunicator.setAccountId("");
        mockAggregatorCommunicator.setIncludeRoomDevices(true);

        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(500000);
        List<AggregatedDevice> devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        mockAggregatorCommunicator.getMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(94, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations("");
        mockAggregatorCommunicator.setZoomRoomTypes(" DigitalSignageOnly");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertTrue(devices.isEmpty());

        mockAggregatorCommunicator.setZoomRoomTypes("");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(26, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());

        mockAggregatorCommunicator.setZoomRoomLocations("SomeLocationThatNoneOfTheDevicesHave");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertTrue(devices.isEmpty());

        mockAggregatorCommunicator.setZoomRoomLocations("");
        mockAggregatorCommunicator.destroy();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(60000);
        devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(26, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }
}
