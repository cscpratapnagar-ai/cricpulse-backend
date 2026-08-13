package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class GetLiveScore {
    private final JdbcTemplate jdbc;

    public GetLiveScore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Score execute(UUID inningsId) {
        Score base = jdbc.queryForObject(
                """
                SELECT id, match_id, innings_number, total_runs, wickets,
                       legal_balls, total_overs, status, target_runs,
                       current_over, current_ball, striker_id,
                       non_striker_id, current_bowler_id
                FROM innings
                WHERE id = ?
                """,
                (rs, row) -> new Score(
                        rs.getObject("id", UUID.class),
                        rs.getObject("match_id", UUID.class),
                        rs.getInt("innings_number"),
                        rs.getInt("total_runs"),
                        rs.getInt("wickets"),
                        rs.getInt("legal_balls"),
                        (Integer) rs.getObject("total_overs"),
                        rs.getString("status"),
                        (Integer) rs.getObject("target_runs"),
                        rs.getInt("current_over"),
                        rs.getInt("current_ball"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getObject("current_bowler_id", UUID.class)
                ),
                inningsId
        );

        List<Batter> batters = jdbc.query(
                """
                SELECT player_id, runs, balls_faced, fours, sixes,
                       strike_rate, is_out, dismissal_type
                FROM innings_batters
                WHERE innings_id = ?
                ORDER BY batting_position NULLS LAST, created_at
                """,
                (rs, row) -> new Batter(
                        rs.getObject("player_id", UUID.class),
                        rs.getInt("runs"),
                        rs.getInt("balls_faced"),
                        rs.getInt("fours"),
                        rs.getInt("sixes"),
                        rs.getBigDecimal("strike_rate"),
                        rs.getBoolean("is_out"),
                        rs.getString("dismissal_type")
                ),
                inningsId
        );

        List<Bowler> bowlers = jdbc.query(
                """
                SELECT player_id, legal_balls, runs_conceded, wickets,
                       wides, no_balls, economy
                FROM innings_bowlers
                WHERE innings_id = ?
                ORDER BY created_at
                """,
                (rs, row) -> new Bowler(
                        rs.getObject("player_id", UUID.class),
                        rs.getInt("legal_balls"),
                        rs.getInt("runs_conceded"),
                        rs.getInt("wickets"),
                        rs.getInt("wides"),
                        rs.getInt("no_balls"),
                        rs.getBigDecimal("economy")
                ),
                inningsId
        );

        List<OverSummary> overs = jdbc.query(
                """
                SELECT over_number, bowler_id, runs, wickets, legal_balls,
                       wides, no_balls, byes, leg_byes, completed
                FROM innings_overs
                WHERE innings_id = ?
                ORDER BY over_number
                """,
                (rs, row) -> new OverSummary(
                        rs.getInt("over_number"),
                        rs.getObject("bowler_id", UUID.class),
                        rs.getInt("runs"),
                        rs.getInt("wickets"),
                        rs.getInt("legal_balls"),
                        rs.getInt("wides"),
                        rs.getInt("no_balls"),
                        rs.getInt("byes"),
                        rs.getInt("leg_byes"),
                        rs.getBoolean("completed")
                ),
                inningsId
        );

        List<RecentBall> recentBalls = jdbc.query(
                """
                SELECT id, over_number, ball_number, striker_id, non_striker_id,
                       bowler_id, bat_runs, extra_runs, extra_type, wicket_type,
                       legal_delivery, total_runs
                FROM deliveries
                WHERE innings_id = ?
                ORDER BY sequence_number DESC NULLS LAST, created_at DESC
                LIMIT 12
                """,
                (rs, row) -> new RecentBall(
                        rs.getObject("id", UUID.class),
                        rs.getInt("over_number"),
                        rs.getInt("ball_number"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getObject("bowler_id", UUID.class),
                        rs.getInt("bat_runs"),
                        rs.getInt("extra_runs"),
                        rs.getString("extra_type"),
                        rs.getString("wicket_type"),
                        rs.getBoolean("legal_delivery"),
                        rs.getInt("total_runs")
                ),
                inningsId
        );

        Partnership partnership = jdbc.query(
                """
                SELECT batter_one_id, batter_two_id, runs, balls
                FROM partnerships
                WHERE innings_id = ? AND is_current = TRUE
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, row) -> new Partnership(
                        rs.getObject("batter_one_id", UUID.class),
                        rs.getObject("batter_two_id", UUID.class),
                        rs.getInt("runs"),
                        rs.getInt("balls")
                ),
                inningsId
        ).stream().findFirst().orElse(null);

        List<FallOfWicket> fallOfWickets = jdbc.query(
                """
                SELECT wicket_number, player_id, runs, over_number, ball_number
                FROM fall_of_wickets
                WHERE innings_id = ?
                ORDER BY wicket_number
                """,
                (rs, row) -> new FallOfWicket(
                        rs.getInt("wicket_number"),
                        rs.getObject("player_id", UUID.class),
                        rs.getInt("runs"),
                        rs.getInt("over_number"),
                        rs.getInt("ball_number")
                ),
                inningsId
        );

        return base.withDetails(batters, bowlers, overs, recentBalls, partnership, fallOfWickets);
    }

    public record Score(
            UUID inningsId,
            UUID matchId,
            int inningsNumber,
            int runs,
            int wickets,
            int legalBalls,
            Integer totalOvers,
            String status,
            Integer targetRuns,
            int currentOver,
            int currentBall,
            UUID strikerId,
            UUID nonStrikerId,
            UUID currentBowlerId,
            List<Batter> batters,
            List<Bowler> bowlers,
            List<OverSummary> overs,
            List<RecentBall> recentBalls,
            Partnership partnership,
            List<FallOfWicket> fallOfWickets
    ) {
        public Score(UUID inningsId, UUID matchId, int inningsNumber, int runs,
                     int wickets, int legalBalls, Integer totalOvers, String status,
                     Integer targetRuns, int currentOver, int currentBall,
                     UUID strikerId, UUID nonStrikerId, UUID currentBowlerId) {
            this(inningsId, matchId, inningsNumber, runs, wickets, legalBalls,
                    totalOvers, status, targetRuns, currentOver, currentBall,
                    strikerId, nonStrikerId, currentBowlerId,
                    List.of(), List.of(), List.of(), List.of(), null, List.of());
        }

        public Score withDetails(List<Batter> batters, List<Bowler> bowlers,
                                 List<OverSummary> overs, List<RecentBall> recentBalls,
                                 Partnership partnership, List<FallOfWicket> fallOfWickets) {
            return new Score(inningsId, matchId, inningsNumber, runs, wickets,
                    legalBalls, totalOvers, status, targetRuns, currentOver,
                    currentBall, strikerId, nonStrikerId, currentBowlerId,
                    batters, bowlers, overs, recentBalls, partnership, fallOfWickets);
        }
    }

    public record Batter(UUID playerId, int runs, int ballsFaced, int fours,
                         int sixes, BigDecimal strikeRate, boolean out,
                         String dismissalType) {}

    public record Bowler(UUID playerId, int legalBalls, int runsConceded,
                         int wickets, int wides, int noBalls,
                         BigDecimal economy) {}

    public record OverSummary(int overNumber, UUID bowlerId, int runs,
                              int wickets, int legalBalls, int wides,
                              int noBalls, int byes, int legByes,
                              boolean completed) {}

    public record RecentBall(UUID deliveryId, int overNumber, int ballNumber,
                             UUID strikerId, UUID nonStrikerId, UUID bowlerId,
                             int batRuns, int extraRuns, String extraType,
                             String wicketType, boolean legalDelivery,
                             int totalRuns) {}

    public record Partnership(UUID batterOneId, UUID batterTwoId,
                              int runs, int balls) {}

    public record FallOfWicket(int wicketNumber, UUID playerId, int runs,
                               int overNumber, int ballNumber) {}
}
