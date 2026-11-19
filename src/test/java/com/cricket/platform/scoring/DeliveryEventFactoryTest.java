package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryEventFactoryTest {
    @Test
    void preservesFielderAttributionInEventAndPayload() {
        UUID fielderId = UUID.randomUUID();
        DeliveryCommand command = new DeliveryCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                0,
                null,
                "CAUGHT",
                UUID.randomUUID(),
                UUID.randomUUID(),
                fielderId,
                UUID.randomUUID()
        );

        DeliveryEvent event = DeliveryEventFactory.create(command, 1L, 1, 0, 1, true);

        assertEquals(fielderId, event.wicketFielderId());
        assertTrue(event.eventPayload().contains(fielderId.toString()));
    }
}
