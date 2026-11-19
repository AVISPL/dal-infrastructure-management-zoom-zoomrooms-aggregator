package com.avispl.symphony.dal.communicator.aggregator;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The cloud host's description of this adapter type (META-INF/symphony-dal/cloud-adapters/zoom.yml): it must name only settings this
 * class can take, since the host refuses to start otherwise.
 */
@Tag("Mock")
public class ZoomCloudDescriptorTest {

    private static final String DESCRIPTOR = "/META-INF/symphony-dal/cloud-adapters/zoom.yml";

    @Test
    public void descriptor_shouldDescribeOnlySettersTheAdapterHas() throws Exception {
        JsonNode descriptor = load();
        Set<String> writable = Arrays.stream(Introspector.getBeanInfo(ZoomRoomsAggregatorCommunicator.class).getPropertyDescriptors())
                .filter(property -> property.getWriteMethod() != null).map(PropertyDescriptor::getName).collect(Collectors.toSet());

        Assertions.assertEquals(ZoomRoomsAggregatorCommunicator.class.getName(), descriptor.path("class-name").asText());
        for (Iterator<String> names = descriptor.path("settings").fieldNames(); names.hasNext(); ) {
            String name = names.next();
            Assertions.assertTrue(writable.contains(name), "no setter for described setting " + name);
        }
    }

    @Test
    public void descriptor_shouldWatchInCallAndKeepTheSecretSecret() throws Exception {
        JsonNode descriptor = load();

        Assertions.assertEquals("inCall", descriptor.path("webhook-watch").get(0).asText());
        Assertions.assertEquals("secret", descriptor.path("settings").path("webhookSecretToken").path("type").asText());
        Assertions.assertEquals("secret", descriptor.path("settings").path("password").path("type").asText());
        Assertions.assertTrue(descriptor.path("settings").path("accountId").path("required").asBoolean());
        Assertions.assertTrue(descriptor.path("setup-steps").size() > 0);
    }

    private static JsonNode load() throws Exception {
        try (InputStream in = ZoomCloudDescriptorTest.class.getResourceAsStream(DESCRIPTOR)) {
            Assertions.assertNotNull(in, DESCRIPTOR + " missing");
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        }
    }
}
