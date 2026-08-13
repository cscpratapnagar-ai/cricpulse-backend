package com.cricket.platform.scoring;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class DeliveryEventFactory {
    private DeliveryEventFactory() {}

    public static DeliveryEvent create(DeliveryCommand command, long sequenceNo, int eventVersion, int overNumber, int ballNumber, boolean legalDelivery) {
        String payload = "{\"commandId\":\"" + command.commandId() + "\",\"batRuns\":" + command.batRuns() + ",\"extraRuns\":" + command.extraRuns() + "}";
        return new DeliveryEvent(
                UUID.randomUUID(), command.inningsId(), sequenceNo, eventVersion,
                "DELIVERY_RECORDED", overNumber, ballNumber,
                command.strikerId(), command.nonStrikerId(), command.bowlerId(),
                command.batRuns(), command.extraRuns(), command.extraType(),
                command.wicketType(), command.dismissedPlayerId(), legalDelivery,
                payload, command.commandId(), command.recordedBy(), OffsetDateTime.now()
        );
    }
}
