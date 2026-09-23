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
                SELECT i.id, i.match_id, i.innings_number, i.total_runs, i.wickets,
                       legal_balls, total_overs, status, target_runs,
                       current_over, current_ball, striker_id, su.full_name AS striker_name,
                       non_striker_id, nsu.full_name AS non_striker_name,
                       current_bowler_id, bu.full_name AS current_bowler_name
                FROM innings i
                LEFT JOIN players sp ON sp.id = i.striker_id
                LEFT JOIN users su ON su.id = sp.user_id
                LEFT JOIN players np ON np.id = i.non_striker_id
                LEFT JOIN users nsu ON nsu.id = np.user_id
                LEFT JOIN players bp ON bp.id = i.current_bowler_id
                LEFT JOIN users bu ON bu.id = bp.user_id
                WHERE i.id = ?
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
                        rs.getString("striker_name"),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getString("non_striker_name"),
                        rs.getObject("current_bowler_id", UUID.class),
                        rs.getString("current_bowler_name")
                ),
                inningsId
        );

        List<Batter> batters = jdbc.query(
                """
                SELECT ib.player_id, u.full_name AS player_name, ib.runs, ib.balls_faced, ib.fours, ib.sixes,
                       strike_rate, is_out, dismissal_type
                FROM innings_batters ib
                JOIN players p ON p.id = ib.player_id
                JOIN users u ON u.id = p.user_id
                WHERE ib.innings_id = ?
                ORDER BY batting_position NULLS LAST, created_at
                """,
                (rs, row) -> new Batter(
                        rs.getObject("player_id", UUID.class),
                        rs.getString("player_name"),
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
                SELECT ib.player_id, u.full_name AS player_name, ib.legal_balls, ib.runs_conceded, ib.wickets,
                       wides, no_balls, economy
                FROM innings_bowlers ib
                JOIN players p ON p.id = ib.player_id
                JOIN users u ON u.id = p.user_id
                WHERE ib.innings_id = ?
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
                SELECT d.id, d.over_number, d.ball_number, d.striker_id, su.full_name AS striker_name,
                       d.non_striker_id, nsu.full_name AS non_striker_name,
                       d.bowler_id, bu.full_name AS bowler_name, d.bat_runs, d.extra_runs, d.extra_type, d.wicket_type,
                       legal_delivery, total_runs
                FROM deliveries d
                JOIN players sp ON sp.id = d.striker_id
                JOIN users su ON su.id = sp.user_id
                JOIN players np ON np.id = d.non_striker_id
                JOIN users nsu ON nsu.id = np.user_id
                JOIN players bp ON bp.id = d.bowler_id
                JOIN users bu ON bu.id = bp.user_id
                WHERE d.innings_id = ?
                ORDER BY sequence_number DESC NULLS LAST, created_at DESC
                LIMIT 12
                """,
                (rs, row) -> new RecentBall(
                        rs.getObject("id", UUID.class),
                        rs.getInt("over_number"),
                        rs.getInt("ball_number"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getString("striker_name"),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getString("non_striker_name"),
                        rs.getObject("bowler_id", UUID.class),
                        rs.getString("bowler_name"),
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
            String strikerName,
            UUID nonStrikerId,
            String nonStrikerName,
            UUID currentBowlerId,
            String currentBowlerName,
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
                     UUID strikerId, UUID nonStrikerId, UUID currentBowlerId,
                     List<Batter> batters, List<Bowler> bowlers, List<OverSummary> overs,
                     List<RecentBall> recentBalls, Partnership partnership,
                     List<FallOfWicket> fallOfWickets) {
            this(inningsId, matchId, inningsNumber, runs, wickets, legalBalls,
                    totalOvers, status, targetRuns, currentOver, currentBall,
                    strikerId, null, nonStrikerId, null, currentBowlerId, null,
                    batters, bowlers, overs, recentBalls, partnership, fallOfWickets);
        }

        public Score(UUID inningsId, UUID matchId, int inningsNumber, int runs,
                     int wickets, int legalBalls, Integer totalOvers, String status,
                     Integer targetRuns, int currentOver, int currentBall,
                     UUID strikerId, String strikerName, UUID nonStrikerId, String nonStrikerName,
                     UUID currentBowlerId, String currentBowlerName) {
            this(inningsId, matchId, inningsNumber, runs, wickets, legalBalls,
                    totalOvers, status, targetRuns, currentOver, currentBall,
                    strikerId, strikerName, nonStrikerId, nonStrikerName, currentBowlerId, currentBowlerName,
                    List.of(), List.of(), List.of(), List.of(), null, List.of());
        }

        public Score withDetails(List<Batter> batters, List<Bowler> bowlers,
                                 List<OverSummary> overs, List<RecentBall> recentBalls,
                                 Partnership partnership, List<FallOfWicket> fallOfWickets) {
            return new Score(inningsId, matchId, inningsNumber, runs, wickets,
                    legalBalls, totalOvers, status, targetRuns, currentOver,
                    currentBall, strikerId, strikerName, nonStrikerId, nonStrikerName, currentBowlerId, currentBowlerName,
                    batters, bowlers, overs, recentBalls, partnership, fallOfWickets);
        }
    }

    public record Batter(UUID playerId, String playerName, int runs, int ballsFaced, int fours,
                         int sixes, BigDecimal strikeRate, boolean out,
                         String dismissalType) {}

    public record Bowler(UUID playerId, String playerName, int legalBalls, int runsConceded,
                         int wickets, int wides, int noBalls,
                         BigDecimal economy) {}

    public record OverSummary(int overNumber, UUID bowlerId, int runs,
                              int wickets, int legalBalls, int wides,
                              int noBalls, int byes, int legByes,
                              boolean completed) {}

    public record RecentBall(UUID deliveryId, int overNumber, int ballNumber,
                             UUID strikerId, String strikerName, UUID nonStrikerId, String nonStrikerName, UUID bowlerId, String bowlerName,
                             int batRuns, int extraRuns, String extraType,
                             String wicketType, boolean legalDelivery,
                             int totalRuns) {}

    public record Partnership(UUID batterOneId, UUID batterTwoId,
                              int runs, int balls) {}

    public record FallOfWicket(int wicketNumber, UUID playerId, int runs,
                               int overNumber, int ballNumber) {}
}
