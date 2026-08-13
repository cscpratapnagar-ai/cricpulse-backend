package com.cricket.platform.scoring;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventFirstProjectionService {
    private final EventFirstDeliveryService eventFirstDeliveryService;
    private final RecordDelivery recordDelivery;

    public EventFirstProjectionService(EventFirstDeliveryService eventFirstDeliveryService,
                                       RecordDelivery recordDelivery) {
        this.eventFirstDeliveryService = eventFirstDeliveryService;
        this.recordDelivery = recordDelivery;
    }

    @Transactional
    public RecordDelivery.DeliveryResponse record(DeliveryCommand command,
                                                   int overNumber,
                                                   int ballNumber) {
        boolean legalDelivery = !"WIDE".equals(command.extraType())
                && !"NO_BALL".equals(command.extraType());

        eventFirstDeliveryService.record(
                command,
                overNumber,
                ballNumber,
                legalDelivery
        );

        return recordDelivery.execute(new RecordDelivery.Request(
                command.inningsId(),
                overNumber,
                ballNumber,
                command.strikerId(),
                command.nonStrikerId(),
                command.bowlerId(),
                command.batRuns(),
                command.extraRuns(),
                command.extraType(),
                command.wicketType(),
                command.dismissedPlayerId(),
                command.newBatterId()
        ));
    }
}
