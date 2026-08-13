package com.cricket.platform.scoring;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class RecordDelivery {
    private final JdbcTemplate jdbc;

    public RecordDelivery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public DeliveryResponse execute(Request request) {
        InningsState innings = jdbc.queryForObject(
                "SELECT id, total_runs, wickets, legal_balls FROM innings WHERE id = ? FOR UPDATE",
                (rs, row) -> new InningsState(rs.getObject("id", UUID.class), rs.getInt("total_runs"),
                        rs.getInt("wickets"), rs.getInt("legal_balls")), request.inningsId());
        validate(request);
        int totalRuns = request.batRuns() + request.extraRuns();
        boolean legal = !"WIDE".equals(request.extraType()) && !"NO_BALL".equals(request.extraType());
        int legalBalls = innings.legalBalls() + (legal ? 1 : 0);
        int wickets = innings.wickets() + (request.wicketType() == null ? 0 : 1);

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deliveries(id, innings_id, over_number, ball_number, striker_id,
                non_striker_id, bowler_id, bat_runs, extra_runs, extra_type, wicket_type, dismissed_player_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.inningsId(), request.overNumber(), request.ballNumber(), request.strikerId(),
                request.nonStrikerId(), request.bowlerId(), request.batRuns(), request.extraRuns(),
                request.extraType(), request.wicketType(), request.dismissedPlayerId());
        jdbc.update("UPDATE innings SET total_runs = ?, wickets = ?, legal_balls = ? WHERE id = ?",
                innings.totalRuns() + totalRuns, wickets, legalBalls, request.inningsId());
        return new DeliveryResponse(id, innings.totalRuns() + totalRuns, wickets, legalBalls);
    }

    private void validate(Request request) {
        if (request.batRuns() > 6) throw new IllegalArgumentException("Bat runs cannot exceed 6");
        if (request.extraType() != null && !java.util.Set.of("WIDE", "NO_BALL", "BYE", "LEG_BYE", "PENALTY").contains(request.extraType()))
            throw new IllegalArgumentException("Unsupported extra type");
        if (request.wicketType() != null && !java.util.Set.of("BOWLED", "CAUGHT", "LBW", "RUN_OUT", "STUMPED", "HIT_WICKET", "RETIRED_HURT").contains(request.wicketType()))
            throw new IllegalArgumentException("Unsupported wicket type");
        if (request.extraRuns() > 0 && request.extraType() == null)
            throw new IllegalArgumentException("Extra type is required when extra runs are recorded");
        if (request.wicketType() != null && request.dismissedPlayerId() == null)
            throw new IllegalArgumentException("Dismissed player is required for a wicket");
    }

    private record InningsState(UUID id, int totalRuns, int wickets, int legalBalls) {}
    public record Request(@NotNull UUID inningsId, @Min(0) int overNumber, @Min(1) int ballNumber,
                          @NotNull UUID strikerId, @NotNull UUID nonStrikerId, @NotNull UUID bowlerId,
                          @Min(0) int batRuns, @Min(0) int extraRuns, String extraType,
                          String wicketType, UUID dismissedPlayerId) {}
    public record DeliveryResponse(UUID deliveryId, int totalRuns, int wickets, int legalBalls) {}
}
