package com.cricket.platform.scoring;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeliveryEvent(
        UUID eventId,
        UUID inningsId,
        long sequenceNo,
        int eventVersion,
        String eventType,
        int overNumber,
        int ballNumber,
        UUID strikerId,
        UUID nonStrikerId,
        UUID bowlerId,
        int batRuns,
        int extraRuns,
        String extraType,
        String wicketType,
        UUID dismissedPlayerId,
        boolean legalDelivery,
        String eventPayload,
        UUID commandId,
        UUID recordedBy,
        OffsetDateTime createdAt
) {
    public DeliveryEvent {
        if (eventId == null || inningsId == null || strikerId == null || nonStrikerId == null || bowlerId == null) {
            throw new IllegalArgumentException("Delivery event identity fields are required");
        }
        if (sequenceNo < 1 || eventVersion < 1) {
            throw new IllegalArgumentException("Delivery sequence and version must be positive");
        }
        if (overNumber < 0 || ballNumber < 1) {
            throw new IllegalArgumentException("Invalid delivery position");
        }
        if (batRuns < 0 || extraRuns < 0) {
            throw new IllegalArgumentException("Runs cannot be negative");
        }
        if (commandId == null) {
            throw new IllegalArgumentException("Command id is required for idempotency");
        }
    }
}
