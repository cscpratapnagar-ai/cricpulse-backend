package com.cricket.platform.scoring;

import java.util.UUID;

public final class ScoringCommandMapper {
    private ScoringCommandMapper() {}

    public static DeliveryCommand toCommand(RecordDelivery.Request request, UUID commandId, UUID recordedBy) {
        return new DeliveryCommand(
                commandId,
                request.inningsId(),
                request.strikerId(),
                request.nonStrikerId(),
                request.bowlerId(),
                request.batRuns(),
                request.extraRuns(),
                request.extraType(),
                request.wicketType(),
                request.dismissedPlayerId(),
                request.newBatterId(),
                recordedBy
        );
    }
}
