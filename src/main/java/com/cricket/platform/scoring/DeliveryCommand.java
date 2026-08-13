package com.cricket.platform.scoring;

import java.util.UUID;

public record DeliveryCommand(
        UUID commandId,
        UUID inningsId,
        UUID strikerId,
        UUID nonStrikerId,
        UUID bowlerId,
        int batRuns,
        int extraRuns,
        String extraType,
        String wicketType,
        UUID dismissedPlayerId,
        UUID newBatterId,
        UUID recordedBy
) {
    public DeliveryCommand {
        if (commandId == null || inningsId == null || strikerId == null || nonStrikerId == null || bowlerId == null) {
            throw new IllegalArgumentException("Delivery command identity fields are required");
        }
        if (batRuns < 0 || batRuns > 6) {
            throw new IllegalArgumentException("Bat runs must be between 0 and 6");
        }
        if (extraRuns < 0) {
            throw new IllegalArgumentException("Extra runs cannot be negative");
        }
        if (strikerId.equals(nonStrikerId())) {
            throw new IllegalArgumentException("Striker and non-striker must be different");
        }
    }
}
