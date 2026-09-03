package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventFirstProjectionService {
    private final EventFirstDeliveryService eventFirstDeliveryService;
    private final RecordDelivery recordDelivery;
    private final JdbcTemplate jdbc;

    public EventFirstProjectionService(EventFirstDeliveryService eventFirstDeliveryService,
                                       RecordDelivery recordDelivery,
                                       JdbcTemplate jdbc) {
        this.eventFirstDeliveryService = eventFirstDeliveryService;
        this.recordDelivery = recordDelivery;
        this.jdbc = jdbc;
    }

    @Transactional
    public Result record(DeliveryCommand command,
                         int overNumber,
                         int ballNumber) {
        boolean legalDelivery = !"WIDE".equals(command.extraType())
                && !"NO_BALL".equals(command.extraType());

        EventFirstDeliveryService.Result eventResult = eventFirstDeliveryService.record(
                command,
                overNumber,
                ballNumber,
                legalDelivery
        );

        if (!eventResult.created()) {
            return new Result(eventResult.event(), false);
        }

        recordDelivery.execute(new RecordDelivery.Request(
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

        return new Result(eventResult.event(), true);
    }

    public record Result(DeliveryEvent event, boolean created) {
    }
}
