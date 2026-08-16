package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class UndoDelivery {
    private final JdbcTemplate jdbc;
    private final GetLiveScore getLiveScore;
    private final InningsLifecycle inningsLifecycle;

    public UndoDelivery(JdbcTemplate jdbc, GetLiveScore getLiveScore, InningsLifecycle inningsLifecycle) {
        this.jdbc = jdbc;
        this.getLiveScore = getLiveScore;
        this.inningsLifecycle = inningsLifecycle;
    }

    @Transactional
    public GetLiveScore.Score execute(UUID inningsId) {
        List<UUID> innings = jdbc.query("SELECT id FROM innings WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class), inningsId);
        if (innings.isEmpty()) throw new IllegalArgumentException("Innings was not found");

        List<UUID> deliveries = jdbc.query("""
                SELECT id FROM deliveries WHERE innings_id = ?
                ORDER BY sequence_number DESC NULLS LAST, created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), inningsId);
        if (deliveries.isEmpty()) return getLiveScore.execute(inningsId);

        UUID deliveryId = deliveries.get(0);
        jdbc.update("DELETE FROM fall_of_wickets WHERE innings_id = ?", inningsId);
        jdbc.update("DELETE FROM innings_batters WHERE innings_id = ?", inningsId);
        jdbc.update("DELETE FROM innings_bowlers WHERE innings_id = ?", inningsId);
        jdbc.update("DELETE FROM innings_overs WHERE innings_id = ?", inningsId);
        jdbc.update("DELETE FROM partnerships WHERE innings_id = ?", inningsId);
        jdbc.update("DELETE FROM deliveries WHERE id = ? AND innings_id = ?", deliveryId, inningsId);

        jdbc.update("""
                UPDATE innings SET
                    total_runs = COALESCE((SELECT SUM(bat_runs + extra_runs) FROM deliveries WHERE innings_id = ?), 0),
                    wickets = COALESCE((SELECT COUNT(*) FROM deliveries WHERE innings_id = ? AND wicket_type IS NOT NULL), 0),
                    legal_balls = COALESCE((SELECT COUNT(*) FROM deliveries WHERE innings_id = ? AND legal_delivery = TRUE), 0),
                    current_over = COALESCE((SELECT MAX(over_number) FROM deliveries WHERE innings_id = ?), 0),
                    current_ball = COALESCE((SELECT MAX(ball_number) FROM deliveries WHERE innings_id = ? AND legal_delivery = TRUE), 0),
                    striker_id = COALESCE((SELECT striker_id FROM deliveries WHERE innings_id = ? ORDER BY sequence_number DESC NULLS LAST, created_at DESC LIMIT 1), striker_id),
                    non_striker_id = COALESCE((SELECT non_striker_id FROM deliveries WHERE innings_id = ? ORDER BY sequence_number DESC NULLS LAST, created_at DESC LIMIT 1), non_striker_id),
                    current_bowler_id = COALESCE((SELECT bowler_id FROM deliveries WHERE innings_id = ? ORDER BY sequence_number DESC NULLS LAST, created_at DESC LIMIT 1), current_bowler_id),
                    status = 'LIVE'
                WHERE id = ?
                """, inningsId, inningsId, inningsId, inningsId, inningsId,
                inningsId, inningsId, inningsId, inningsId);

        rebuildOverSummaries(inningsId);
        rebuildBatterSummaries(inningsId);
        rebuildBowlerSummaries(inningsId);
        rebuildPartnership(inningsId);
        rebuildFallOfWickets(inningsId);
        inningsLifecycle.evaluate(inningsId);
        return getLiveScore.execute(inningsId);
    }

    private void rebuildOverSummaries(UUID inningsId) {
        jdbc.update("""
                INSERT INTO innings_overs(innings_id, over_number, bowler_id, runs, wickets, legal_balls, wides, no_balls, byes, leg_byes, completed)
                SELECT d.innings_id, d.over_number,
                       (SELECT x.bowler_id FROM deliveries x
                        WHERE x.innings_id = d.innings_id AND x.over_number = d.over_number
                        ORDER BY x.sequence_number DESC NULLS LAST, x.created_at DESC LIMIT 1),
                       SUM(d.bat_runs + d.extra_runs),
                       SUM(CASE WHEN d.wicket_type IS NOT NULL THEN 1 ELSE 0 END),
                       SUM(CASE WHEN d.legal_delivery THEN 1 ELSE 0 END),
                       SUM(CASE WHEN d.extra_type = 'WIDE' THEN d.extra_runs ELSE 0 END),
                       SUM(CASE WHEN d.extra_type = 'NO_BALL' THEN d.extra_runs ELSE 0 END),
                       SUM(CASE WHEN d.extra_type = 'BYE' THEN d.extra_runs ELSE 0 END),
                       SUM(CASE WHEN d.extra_type = 'LEG_BYE' THEN d.extra_runs ELSE 0 END),
                       SUM(CASE WHEN d.legal_delivery THEN 1 ELSE 0 END) >= 6
                FROM deliveries d WHERE d.innings_id = ? GROUP BY d.innings_id, d.over_number
                """, inningsId);
    }

    private void rebuildBatterSummaries(UUID inningsId) {
        jdbc.update("""
                INSERT INTO innings_batters(innings_id, player_id, runs, balls_faced, fours, sixes, is_out, dismissal_type, dismissal_delivery_id, strike_rate)
                SELECT innings_id, striker_id, SUM(bat_runs),
                       SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END),
                       SUM(CASE WHEN bat_runs = 4 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN bat_runs = 6 THEN 1 ELSE 0 END),
                       BOOL_OR(wicket_type IS NOT NULL AND dismissed_player_id = striker_id),
                       MAX(CASE WHEN wicket_type IS NOT NULL AND dismissed_player_id = striker_id THEN wicket_type END),
                       (array_agg(id ORDER BY sequence_number DESC NULLS LAST, created_at DESC) FILTER (WHERE wicket_type IS NOT NULL AND dismissed_player_id = striker_id))[1],
                       CASE WHEN SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END) = 0 THEN 0
                            ELSE ROUND(SUM(bat_runs)::numeric * 100 / SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END), 2) END
                FROM deliveries WHERE innings_id = ? GROUP BY innings_id, striker_id
                """, inningsId);
    }

    private void rebuildBowlerSummaries(UUID inningsId) {
        jdbc.update("""
                INSERT INTO innings_bowlers(innings_id, player_id, legal_balls, runs_conceded, wickets, wides, no_balls, economy)
                SELECT innings_id, bowler_id,
                       SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END),
                       SUM(CASE WHEN extra_type IN ('BYE', 'LEG_BYE') THEN bat_runs ELSE bat_runs + extra_runs END),
                       SUM(CASE WHEN wicket_type IN ('BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET') THEN 1 ELSE 0 END),
                       SUM(CASE WHEN extra_type = 'WIDE' THEN extra_runs ELSE 0 END),
                       SUM(CASE WHEN extra_type = 'NO_BALL' THEN extra_runs ELSE 0 END),
                       CASE WHEN SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END) = 0 THEN 0
                            ELSE ROUND(SUM(CASE WHEN extra_type IN ('BYE', 'LEG_BYE') THEN bat_runs ELSE bat_runs + extra_runs END)::numeric * 6 / SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END), 2) END
                FROM deliveries WHERE innings_id = ? GROUP BY innings_id, bowler_id
                """, inningsId);
    }

    private void rebuildPartnership(UUID inningsId) {
        // A UUID cannot be passed to MAX(). Find the last wicket by ordered row
        // selection, then rebuild only deliveries after that wicket.
        jdbc.update("""
                INSERT INTO partnerships(innings_id, batter_one_id, batter_two_id, runs, balls, is_current)
                SELECT ?, latest.striker_id, latest.non_striker_id,
                       COALESCE(SUM(d.bat_runs + d.extra_runs), 0),
                       COALESCE(SUM(CASE WHEN d.legal_delivery THEN 1 ELSE 0 END), 0),
                       TRUE
                FROM deliveries d
                CROSS JOIN LATERAL (
                    SELECT x.striker_id, x.non_striker_id
                    FROM deliveries x
                    WHERE x.innings_id = ?
                    ORDER BY x.sequence_number DESC NULLS LAST, x.created_at DESC LIMIT 1
                ) latest
                WHERE d.innings_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM deliveries w
                      WHERE w.innings_id = ?
                        AND w.wicket_type IS NOT NULL
                        AND (w.created_at > d.created_at
                             OR (w.created_at = d.created_at AND w.id > d.id))
                  )
                GROUP BY latest.striker_id, latest.non_striker_id
                """, inningsId, inningsId, inningsId, inningsId);
    }

    private void rebuildFallOfWickets(UUID inningsId) {
        jdbc.update("""
                INSERT INTO fall_of_wickets(innings_id, wicket_number, player_id, runs, over_number, ball_number, delivery_id)
                SELECT innings_id, ROW_NUMBER() OVER (ORDER BY sequence_number), dismissed_player_id,
                       (SELECT COALESCE(SUM(x.bat_runs + x.extra_runs), 0) FROM deliveries x WHERE x.innings_id = d.innings_id AND x.sequence_number <= d.sequence_number),
                       over_number, ball_number, id
                FROM deliveries d
                WHERE innings_id = ? AND wicket_type IS NOT NULL AND dismissed_player_id IS NOT NULL
                """, inningsId);
    }
}
