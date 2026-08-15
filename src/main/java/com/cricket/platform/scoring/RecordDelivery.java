package com.cricket.platform.scoring;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Component
public class RecordDelivery {
    private static final Set<String> EXTRAS = Set.of("WIDE", "NO_BALL", "BYE", "LEG_BYE", "PENALTY");
    private static final Set<String> WICKETS = Set.of(
            "BOWLED", "CAUGHT", "LBW", "RUN_OUT", "STUMPED", "HIT_WICKET", "RETIRED_HURT"
    );
    private static final Set<String> BOWLER_WICKETS = Set.of(
            "BOWLED", "CAUGHT", "LBW", "STUMPED", "HIT_WICKET"
    );

    private final JdbcTemplate jdbc;

    public RecordDelivery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public DeliveryResponse execute(Request request) {
        validate(request);

        UUID dismissedPlayerId = request.dismissedPlayerId() != null
                ? request.dismissedPlayerId()
                : (request.wicketType() == null ? null : request.strikerId());

        request = new Request(
                request.inningsId(), request.overNumber(), request.ballNumber(),
                request.strikerId(), request.nonStrikerId(), request.bowlerId(),
                request.batRuns(), request.extraRuns(), request.extraType(),
                request.wicketType(), dismissedPlayerId, request.newBatterId()
        );

        InningsState innings = jdbc.queryForObject(
                """
                SELECT id, total_runs, wickets, legal_balls, current_over, current_ball,
                       striker_id, non_striker_id, current_bowler_id, status
                FROM innings
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new InningsState(
                        rs.getObject("id", UUID.class),
                        rs.getInt("total_runs"),
                        rs.getInt("wickets"),
                        rs.getInt("legal_balls"),
                        rs.getInt("current_over"),
                        rs.getInt("current_ball"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getObject("current_bowler_id", UUID.class),
                        rs.getString("status")
                ),
                request.inningsId()
        );

        if (innings == null) throw new IllegalArgumentException("Innings was not found");
        if (!"LIVE".equals(innings.status())) throw new IllegalArgumentException("Innings is not live");

        validateAgainstCurrentState(request, innings);

        int totalRuns = request.batRuns() + request.extraRuns();
        boolean legal = !"WIDE".equals(request.extraType()) && !"NO_BALL".equals(request.extraType());
        int legalBalls = innings.legalBalls() + (legal ? 1 : 0);
        int wickets = innings.wickets() + (request.wicketType() == null ? 0 : 1);
        int overNumber = legal ? legalBalls / 6 : Math.max(0, innings.currentOver());
        int ballNumber = legal ? ((legalBalls - 1) % 6) + 1 : Math.max(1, innings.currentBall());

        UUID deliveryId = UUID.randomUUID();
        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM deliveries WHERE innings_id = ?",
                Integer.class, request.inningsId());

        jdbc.update(
                """
                INSERT INTO deliveries(
                    id, innings_id, over_number, ball_number,
                    striker_id, non_striker_id, bowler_id,
                    bat_runs, extra_runs, extra_type, wicket_type,
                    dismissed_player_id, sequence_number, legal_delivery,
                    total_runs, is_boundary, is_four, is_six
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                deliveryId, request.inningsId(), overNumber, ballNumber,
                request.strikerId(), request.nonStrikerId(), request.bowlerId(),
                request.batRuns(), request.extraRuns(), request.extraType(), request.wicketType(),
                request.dismissedPlayerId(), sequence, legal, totalRuns,
                request.batRuns() == 4, request.batRuns() == 4, request.batRuns() == 6
        );

        jdbc.update(
                """
                UPDATE innings SET total_runs = ?, wickets = ?, legal_balls = ?,
                    current_over = ?, current_ball = ?, current_bowler_id = ?
                WHERE id = ?
                """,
                innings.totalRuns() + totalRuns, wickets, legalBalls,
                legal ? legalBalls / 6 : innings.currentOver(),
                legal ? ((legalBalls - 1) % 6) + 1 : Math.max(1, innings.currentBall()),
                request.bowlerId(), request.inningsId()
        );

        updateOverSummary(request, overNumber, legal, totalRuns);
        updateBatterStats(request, deliveryId, legal);
        updateBowlerStats(request, legal, totalRuns);
        updatePartnership(request, legal, totalRuns);

        if (request.wicketType() != null) {
            recordFallOfWicket(request, deliveryId, innings.totalRuns() + totalRuns, overNumber, ballNumber);
        }

        UUID nextStriker = request.strikerId();
        UUID nextNonStriker = request.nonStrikerId();

        if (totalRuns % 2 != 0) {
            UUID tmp = nextStriker;
            nextStriker = nextNonStriker;
            nextNonStriker = tmp;
        }
        if (legal && legalBalls % 6 == 0) {
            UUID tmp = nextStriker;
            nextStriker = nextNonStriker;
            nextNonStriker = tmp;
        }

        if (request.wicketType() != null) {
            if (wickets >= 10) {
                if (request.dismissedPlayerId().equals(nextStriker)) nextStriker = null;
                if (request.dismissedPlayerId().equals(nextNonStriker)) nextNonStriker = null;
            } else {
                if (request.dismissedPlayerId().equals(nextStriker)) nextStriker = request.newBatterId();
                if (request.dismissedPlayerId().equals(nextNonStriker)) nextNonStriker = request.newBatterId();
                createNewPartnership(nextStriker, nextNonStriker, request.inningsId());
            }
        }

        jdbc.update(
                "UPDATE innings SET striker_id = ?, non_striker_id = ? WHERE id = ?",
                nextStriker, nextNonStriker, request.inningsId());

        return new DeliveryResponse(
                deliveryId, innings.totalRuns() + totalRuns, wickets, legalBalls,
                overNumber, ballNumber, nextStriker, nextNonStriker);
    }

    private void validateAgainstCurrentState(Request request, InningsState innings) {
        if (innings.strikerId() == null || innings.nonStrikerId() == null) {
            throw new IllegalArgumentException("Current striker and non-striker must be set");
        }
        if (!innings.strikerId().equals(request.strikerId()) || !innings.nonStrikerId().equals(request.nonStrikerId())) {
            throw new IllegalArgumentException("Striker/non-striker does not match the current innings state");
        }

        boolean overBoundary = innings.legalBalls() > 0 && innings.legalBalls() % 6 == 0;
        if (innings.currentBowlerId() != null) {
            if (overBoundary && innings.currentBowlerId().equals(request.bowlerId())) {
                throw new IllegalArgumentException("A new bowler must be selected for the next over");
            }
            if (!overBoundary && !innings.currentBowlerId().equals(request.bowlerId())) {
                throw new IllegalArgumentException("Bowler cannot change before the over is completed");
            }
        }

        if (request.wicketType() != null) {
            if (!request.dismissedPlayerId().equals(request.strikerId())
                    && !request.dismissedPlayerId().equals(request.nonStrikerId())) {
                throw new IllegalArgumentException("Dismissed player must be the current striker or non-striker");
            }
            int nextWicket = innings.wickets() + 1;
            if (nextWicket < 10 && request.newBatterId() == null) {
                throw new IllegalArgumentException("New batter is required after a wicket");
            }
            if (nextWicket >= 10 && request.newBatterId() != null) {
                throw new IllegalArgumentException("No new batter is allowed after the 10th wicket");
            }
            if (request.newBatterId() != null &&
                    (request.newBatterId().equals(request.strikerId())
                    || request.newBatterId().equals(request.nonStrikerId())
                    || request.newBatterId().equals(request.dismissedPlayerId()))) {
                throw new IllegalArgumentException("New batter must be a different available player");
            }
        } else if (request.newBatterId() != null || request.dismissedPlayerId() != null) {
            throw new IllegalArgumentException("Wicket fields can only be supplied for a wicket delivery");
        }
    }

    private void updateOverSummary(Request request, int overNumber, boolean legal, int totalRuns) {
        jdbc.update(
                """
                INSERT INTO innings_overs(innings_id, over_number, bowler_id, runs, wickets, legal_balls, wides, no_balls, byes, leg_byes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (innings_id, over_number) DO UPDATE SET
                    runs = innings_overs.runs + EXCLUDED.runs,
                    wickets = innings_overs.wickets + EXCLUDED.wickets,
                    legal_balls = innings_overs.legal_balls + EXCLUDED.legal_balls,
                    wides = innings_overs.wides + EXCLUDED.wides,
                    no_balls = innings_overs.no_balls + EXCLUDED.no_balls,
                    byes = innings_overs.byes + EXCLUDED.byes,
                    leg_byes = innings_overs.leg_byes + EXCLUDED.leg_byes,
                    completed = innings_overs.legal_balls + EXCLUDED.legal_balls >= 6
                """,
                request.inningsId(), overNumber, request.bowlerId(), totalRuns,
                request.wicketType() == null ? 0 : 1, legal ? 1 : 0,
                "WIDE".equals(request.extraType()) ? request.extraRuns() : 0,
                "NO_BALL".equals(request.extraType()) ? request.extraRuns() : 0,
                "BYE".equals(request.extraType()) ? request.extraRuns() : 0,
                "LEG_BYE".equals(request.extraType()) ? request.extraRuns() : 0);
    }

    private void updateBatterStats(Request request, UUID deliveryId, boolean legal) {
        jdbc.update(
                """
                INSERT INTO innings_batters(innings_id, player_id, runs, balls_faced, fours, sixes, is_out, dismissal_type, dismissal_delivery_id, strike_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (innings_id, player_id) DO UPDATE SET
                    runs = innings_batters.runs + EXCLUDED.runs,
                    balls_faced = innings_batters.balls_faced + EXCLUDED.balls_faced,
                    fours = innings_batters.fours + EXCLUDED.fours,
                    sixes = innings_batters.sixes + EXCLUDED.sixes,
                    is_out = innings_batters.is_out OR EXCLUDED.is_out,
                    dismissal_type = COALESCE(EXCLUDED.dismissal_type, innings_batters.dismissal_type),
                    dismissal_delivery_id = COALESCE(EXCLUDED.dismissal_delivery_id, innings_batters.dismissal_delivery_id),
                    strike_rate = CASE
                        WHEN innings_batters.balls_faced + EXCLUDED.balls_faced = 0 THEN 0
                        ELSE ROUND(((innings_batters.runs + EXCLUDED.runs)::numeric * 100) /
                                   (innings_batters.balls_faced + EXCLUDED.balls_faced), 2)
                    END
                """,
                request.inningsId(), request.strikerId(), request.batRuns(), legal ? 1 : 0,
                request.batRuns() == 4 ? 1 : 0, request.batRuns() == 6 ? 1 : 0,
                request.wicketType() != null && request.dismissedPlayerId().equals(request.strikerId()),
                request.wicketType(), request.wicketType() == null ? null : deliveryId);
    }

    private void updateBowlerStats(Request request, boolean legal, int totalRuns) {
        int bowlerRuns = switch (request.extraType() == null ? "" : request.extraType()) {
            case "BYE", "LEG_BYE" -> request.batRuns();
            default -> totalRuns;
        };
        jdbc.update(
                """
                INSERT INTO innings_bowlers(innings_id, player_id, legal_balls, runs_conceded, wickets, wides, no_balls, economy)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (innings_id, player_id) DO UPDATE SET
                    legal_balls = innings_bowlers.legal_balls + EXCLUDED.legal_balls,
                    runs_conceded = innings_bowlers.runs_conceded + EXCLUDED.runs_conceded,
                    wickets = innings_bowlers.wickets + EXCLUDED.wickets,
                    wides = innings_bowlers.wides + EXCLUDED.wides,
                    no_balls = innings_bowlers.no_balls + EXCLUDED.no_balls,
                    economy = CASE
                        WHEN innings_bowlers.legal_balls + EXCLUDED.legal_balls = 0 THEN 0
                        ELSE ROUND(((innings_bowlers.runs_conceded + EXCLUDED.runs_conceded)::numeric * 6) /
                                   (innings_bowlers.legal_balls + EXCLUDED.legal_balls), 2)
                    END
                """,
                request.inningsId(), request.bowlerId(), legal ? 1 : 0, bowlerRuns,
                isBowlerWicket(request.wicketType()) ? 1 : 0,
                "WIDE".equals(request.extraType()) ? request.extraRuns() : 0,
                "NO_BALL".equals(request.extraType()) ? request.extraRuns() : 0);
    }

    private void updatePartnership(Request request, boolean legal, int totalRuns) {
        jdbc.update(
                """
                UPDATE partnerships
                SET runs = runs + ?, balls = balls + ?
                WHERE innings_id = ? AND is_current = TRUE
                """,
                totalRuns, legal ? 1 : 0, request.inningsId());
    }

    private void recordFallOfWicket(Request request, UUID deliveryId, int score, int overNumber, int ballNumber) {
        Integer wicketNumber = jdbc.queryForObject(
                "SELECT COALESCE(MAX(wicket_number), 0) + 1 FROM fall_of_wickets WHERE innings_id = ?",
                Integer.class, request.inningsId());
        jdbc.update(
                "INSERT INTO fall_of_wickets(innings_id, wicket_number, player_id, runs, over_number, ball_number, delivery_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                request.inningsId(), wicketNumber, request.dismissedPlayerId(), score, overNumber, ballNumber, deliveryId);
        jdbc.update(
                "UPDATE partnerships SET is_current = FALSE WHERE innings_id = ? AND is_current = TRUE",
                request.inningsId());
    }

    private void createNewPartnership(UUID batterOneId, UUID batterTwoId, UUID inningsId) {
        if (batterOneId == null || batterTwoId == null || batterOneId.equals(batterTwoId)) return;
        jdbc.update(
                "INSERT INTO partnerships(innings_id, batter_one_id, batter_two_id, runs, balls, is_current) VALUES (?, ?, ?, 0, 0, TRUE)",
                inningsId, batterOneId, batterTwoId);
    }

    private boolean isBowlerWicket(String wicketType) {
        return wicketType != null && BOWLER_WICKETS.contains(wicketType);
    }

    private void validate(Request request) {
        if (request == null) throw new IllegalArgumentException("Delivery request is required");
        if (request.batRuns() < 0 || request.batRuns() > 6) {
            throw new IllegalArgumentException("Bat runs must be between 0 and 6");
        }
        if (request.extraRuns() < 0) throw new IllegalArgumentException("Extra runs cannot be negative");
        if (request.extraType() != null && !EXTRAS.contains(request.extraType())) {
            throw new IllegalArgumentException("Unsupported extra type");
        }
        if (request.wicketType() != null && !WICKETS.contains(request.wicketType())) {
            throw new IllegalArgumentException("Unsupported wicket type");
        }
        if (request.extraRuns() > 0 && request.extraType() == null) {
            throw new IllegalArgumentException("Extra type is required when extra runs are recorded");
        }
        if ("WIDE".equals(request.extraType()) && request.batRuns() != 0) {
            throw new IllegalArgumentException("Wide cannot contain bat runs");
        }
        if ("WIDE".equals(request.extraType()) && request.extraRuns() < 1) {
            throw new IllegalArgumentException("Wide must contain at least one extra run");
        }
        if ("NO_BALL".equals(request.extraType()) && request.extraRuns() < 1) {
            throw new IllegalArgumentException("No-ball must contain at least one extra run");
        }
        if (request.overNumber() < 0 || request.ballNumber() < 1) {
            throw new IllegalArgumentException("Invalid over/ball number");
        }
    }

    private record InningsState(
            UUID id, int totalRuns, int wickets, int legalBalls, int currentOver,
            int currentBall, UUID strikerId, UUID nonStrikerId, UUID currentBowlerId, String status
    ) {}

    public record Request(
            @NotNull UUID inningsId, @Min(0) int overNumber, @Min(1) int ballNumber,
            @NotNull UUID strikerId, @NotNull UUID nonStrikerId, @NotNull UUID bowlerId,
            @Min(0) int batRuns, @Min(0) int extraRuns, String extraType,
            String wicketType, UUID dismissedPlayerId, UUID newBatterId
    ) {}

    public record DeliveryResponse(
            UUID deliveryId, int totalRuns, int wickets, int legalBalls,
            int overNumber, int ballNumber, UUID strikerId, UUID nonStrikerId
    ) {}
}
