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

import java.util.List;

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
        mockAggregatorCommunicator.internalInit();
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.authenticate();
    }

    @Test
    public void getAggregatedDevicesTest() throws Exception {
        List<AggregatedDevice> aggregatedDevices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertEquals(18, aggregatedDevices.size());
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
}
