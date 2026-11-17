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
        // Serialize reconciliation with all other scoring writes for this innings.
        // A legacy delivery may exist in the projection table without a matching
        // delivery_events row (for example, it was recorded before event-first
        // idempotency was introduced). A replay must acknowledge that exact
        // delivery instead of failing on the already-consumed ball position.
        jdbc.queryForObject(
                "SELECT id FROM innings WHERE id = ? FOR UPDATE",
                java.util.UUID.class,
                command.inningsId()
        );

        boolean legalDelivery = !"WIDE".equals(command.extraType())
                && !"NO_BALL".equals(command.extraType());

        Boolean alreadyProjected = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM deliveries
                    WHERE innings_id = ?
                      AND over_number = ?
                      AND ball_number = ?
                      AND striker_id = ?
                      AND non_striker_id = ?
                      AND bowler_id = ?
                      AND bat_runs = ?
                      AND extra_runs = ?
                      AND extra_type IS NOT DISTINCT FROM ?
                      AND wicket_type IS NOT DISTINCT FROM ?
                      AND dismissed_player_id IS NOT DISTINCT FROM ?
                      AND legal_delivery = ?
                )
                """,
                Boolean.class,
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
                legalDelivery
        );

        if (Boolean.TRUE.equals(alreadyProjected)) {
            return new Result(null, false);
        }

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
