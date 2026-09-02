package com.cricket.platform.scoring;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventFirstProjectionService {
    private final EventFirstDeliveryService eventFirstDeliveryService;
    private final RecordDelivery recordDelivery;
    private final LiveScoreBroadcastPublisher liveScoreBroadcastPublisher;

    public EventFirstProjectionService(EventFirstDeliveryService eventFirstDeliveryService,
                                       RecordDelivery recordDelivery,
                                       LiveScoreBroadcastPublisher liveScoreBroadcastPublisher) {
        this.eventFirstDeliveryService = eventFirstDeliveryService;
        this.recordDelivery = recordDelivery;
        this.liveScoreBroadcastPublisher = liveScoreBroadcastPublisher;
    }

    @Transactional
    public RecordDelivery.DeliveryResponse record(DeliveryCommand command,
                                                   int overNumber,
                                                   int ballNumber) {
        boolean legalDelivery = !"WIDE".equals(command.extraType())
                && !"NO_BALL".equals(command.extraType());

        DeliveryEvent event = eventFirstDeliveryService.record(
                command,
                overNumber,
                ballNumber,
                legalDelivery
        );

        RecordDelivery.DeliveryResponse response = recordDelivery.execute(new RecordDelivery.Request(
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

        liveScoreBroadcastPublisher.publishAfterCommit(new LiveScoreCommittedEvent(
                event.inningsId(),
                event.eventId(),
                event.sequenceNo(),
                event.eventVersion()
        ));

        return response;
    }
}
