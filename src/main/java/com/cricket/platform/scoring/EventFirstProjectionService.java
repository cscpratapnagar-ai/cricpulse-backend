package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventFirstProjectionService {
    private final EventFirstDeliveryService eventFirstDeliveryService;
    private final RecordDelivery recordDelivery;
    private final LiveScoreBroadcastPublisher liveScoreBroadcastPublisher;
    private final MatchResultLifecycle matchResultLifecycle;
    private final JdbcTemplate jdbc;

    public EventFirstProjectionService(EventFirstDeliveryService eventFirstDeliveryService,
                                       RecordDelivery recordDelivery,
                                       LiveScoreBroadcastPublisher liveScoreBroadcastPublisher,
                                       MatchResultLifecycle matchResultLifecycle,
                                       JdbcTemplate jdbc) {
        this.eventFirstDeliveryService = eventFirstDeliveryService;
        this.recordDelivery = recordDelivery;
        this.liveScoreBroadcastPublisher = liveScoreBroadcastPublisher;
        this.matchResultLifecycle = matchResultLifecycle;
        this.jdbc = jdbc;
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

        jdbc.update("UPDATE innings SET state_version = state_version + 1 WHERE id = ?", command.inningsId());

        MatchResultLifecycle.Evaluation evaluation = matchResultLifecycle.evaluate(command.inningsId());

        liveScoreBroadcastPublisher.publishAfterCommit(new LiveScoreCommittedEvent(
                event.inningsId(),
                event.eventId(),
                event.sequenceNo(),
                event.eventVersion(),
                evaluation.eventType().name()
        ));

        return response;
    }
}
