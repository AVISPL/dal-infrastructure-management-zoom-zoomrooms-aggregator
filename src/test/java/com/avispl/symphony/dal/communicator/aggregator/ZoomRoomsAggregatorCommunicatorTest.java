package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.platform.commons.util.StringUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Tag("test")
public class ZoomRoomsAggregatorCommunicatorTest {
    static ZoomRoomsAggregatorCommunicator mockAggregatorCommunicator;

    @BeforeEach
    public void init() throws Exception {
        mockAggregatorCommunicator = new ZoomRoomsAggregatorCommunicator();
        mockAggregatorCommunicator.setPassword("***REMOVED***");
        mockAggregatorCommunicator.setHost("api.zoom.us");
        mockAggregatorCommunicator.setProtocol("https");
        mockAggregatorCommunicator.setPort(443);
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.authenticate();
    }

    @Test
    public void getDevicesWithFilteringTest() throws Exception {
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
    public void getDevicesWithLocationsFilteringTest() throws Exception {
        mockAggregatorCommunicator.setDisplayRoomSettings(true);
        mockAggregatorCommunicator.setZoomRoomLocations("Chicago,Colorado");
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
        mockAggregatorCommunicator.setMetricsRetrievalTimeout(60000);
        mockAggregatorCommunicator.setDisplayRoomSettings(true);
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
    public void pingTest() throws Exception {
        int pingLatency = mockAggregatorCommunicator.ping();
        Assert.assertNotEquals(0, pingLatency);
        System.out.println("Ping latency calculated: " + pingLatency);
    }
    @Test
    public void getAggregatorDataTest() throws Exception {
        List<Statistics> statistics = mockAggregatorCommunicator.getMultipleStatistics();
        Assert.assertEquals(1, statistics.size());
        Assert.assertNotNull(statistics.get(0));
    }

    @Test
    public void controlRoomSettingTest() throws Exception {
        String roomId = "KJmeDJOMQDmh3gczMXCxFQ";
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
}
