package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GetBowlerSpellAnalysis {
    private final JdbcTemplate jdbc;

    public GetBowlerSpellAnalysis(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BowlerSpell> get(UUID matchId) {
        List<BowlerRow> bowlers = jdbc.query("""
                SELECT i.id AS innings_id, i.innings_number, bt.name AS batting_team,
                       ib.player_id AS bowler_id, u.full_name AS bowler_name,
                       ib.legal_balls, ib.runs_conceded, ib.wickets,
                       ib.wides, ib.no_balls
                FROM innings_bowlers ib
                JOIN innings i ON i.id = ib.innings_id
                JOIN teams bt ON bt.id = i.batting_team_id
                JOIN players p ON p.id = ib.player_id
                LEFT JOIN users u ON u.id = p.user_id
                WHERE i.match_id = ?
                ORDER BY i.innings_number, ib.legal_balls DESC, u.full_name
                """, (rs, row) -> new BowlerRow(
                rs.getObject("innings_id", UUID.class),
                rs.getInt("innings_number"),
                rs.getString("batting_team"),
                rs.getObject("bowler_id", UUID.class),
                rs.getString("bowler_name") == null ? "Unknown bowler" : rs.getString("bowler_name"),
                rs.getInt("legal_balls"),
                rs.getInt("runs_conceded"),
                rs.getInt("wickets"),
                rs.getInt("wides"),
                rs.getInt("no_balls")
        ), matchId);

        List<OverRow> overRows = jdbc.query("""
                SELECT d.innings_id, d.bowler_id, d.over_number,
                       SUM(CASE WHEN d.legal_delivery THEN 1 ELSE 0 END) AS legal_balls,
                       SUM(CASE WHEN d.extra_type IN ('BYE', 'LEG_BYE')
                                THEN d.bat_runs
                                ELSE d.bat_runs + d.extra_runs END) AS runs_conceded,
                       SUM(CASE WHEN d.wicket_type IN
                                    ('BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET')
                                THEN 1 ELSE 0 END) AS bowler_wickets
                FROM deliveries d
                JOIN innings i ON i.id = d.innings_id
                WHERE i.match_id = ?
                GROUP BY d.innings_id, d.bowler_id, d.over_number
                ORDER BY i.innings_number, d.over_number
                """, (rs, row) -> new OverRow(
                rs.getObject("innings_id", UUID.class),
                rs.getObject("bowler_id", UUID.class),
                rs.getInt("over_number") + 1,
                rs.getInt("legal_balls"),
                rs.getInt("runs_conceded"),
                rs.getInt("bowler_wickets")
        ), matchId);

        List<WicketRow> wicketRows = jdbc.query("""
                SELECT d.innings_id, d.bowler_id, d.over_number + 1 AS over_number,
                       d.ball_number, d.wicket_type,
                       COALESCE(u.full_name, 'Unknown batter') AS dismissed_batter,
                       d.wicket_type IN ('BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET')
                           AS credited_to_bowler
                FROM deliveries d
                JOIN innings i ON i.id = d.innings_id
                LEFT JOIN players p ON p.id = d.dismissed_player_id
                LEFT JOIN users u ON u.id = p.user_id
                WHERE i.match_id = ? AND d.wicket_type IS NOT NULL
                ORDER BY i.innings_number, d.sequence_number
                """, (rs, row) -> new WicketRow(
                rs.getObject("innings_id", UUID.class),
                rs.getObject("bowler_id", UUID.class),
                rs.getInt("over_number"),
                rs.getInt("ball_number"),
                rs.getString("wicket_type"),
                rs.getString("dismissed_batter"),
                rs.getBoolean("credited_to_bowler")
        ), matchId);

        Map<Key, List<OverFigure>> oversByBowler = new LinkedHashMap<>();
        for (OverRow row : overRows) {
            oversByBowler.computeIfAbsent(new Key(row.inningsId(), row.bowlerId()), ignored -> new ArrayList<>())
                    .add(new OverFigure(row.overNumber(), row.legalBalls(), row.runsConceded(),
                            row.bowlerWickets(), row.legalBalls() == 6 && row.runsConceded() == 0));
        }

        Map<Key, List<WicketEvent>> wicketsByBowler = new LinkedHashMap<>();
        for (WicketRow row : wicketRows) {
            wicketsByBowler.computeIfAbsent(new Key(row.inningsId(), row.bowlerId()), ignored -> new ArrayList<>())
                    .add(new WicketEvent(row.overNumber(), row.ballNumber(), row.wicketType(),
                            row.dismissedBatter(), row.creditedToBowler()));
        }

        List<BowlerSpell> result = new ArrayList<>();
        for (BowlerRow bowler : bowlers) {
            Key key = new Key(bowler.inningsId(), bowler.bowlerId());
            List<OverFigure> overFigures = List.copyOf(oversByBowler.getOrDefault(key, List.of()));
            int maidens = (int) overFigures.stream().filter(OverFigure::maiden).count();
            double economy = bowler.legalBalls() == 0 ? 0.0
                    : Math.round((bowler.runsConceded() * 6.0 / bowler.legalBalls()) * 100.0) / 100.0;
            result.add(new BowlerSpell(
                    bowler.inningsNumber(), bowler.battingTeam(), bowler.bowlerId(), bowler.bowlerName(),
                    bowler.legalBalls(), oversText(bowler.legalBalls()), bowler.runsConceded(),
                    bowler.wickets(), economy, maidens, bowler.wides(), bowler.noBalls(),
                    overFigures, List.copyOf(wicketsByBowler.getOrDefault(key, List.of()))
            ));
        }
        return List.copyOf(result);
    }

    static String oversText(int legalBalls) {
        return (legalBalls / 6) + "." + (legalBalls % 6);
    }

    private record Key(UUID inningsId, UUID bowlerId) {}
    private record BowlerRow(UUID inningsId, int inningsNumber, String battingTeam, UUID bowlerId, String bowlerName,
                             int legalBalls, int runsConceded, int wickets, int wides, int noBalls) {}
    private record OverRow(UUID inningsId, UUID bowlerId, int overNumber, int legalBalls,
                           int runsConceded, int bowlerWickets) {}
    private record WicketRow(UUID inningsId, UUID bowlerId, int overNumber, int ballNumber,
                             String wicketType, String dismissedBatter, boolean creditedToBowler) {}

    public record OverFigure(int overNumber, int legalBalls, int runsConceded, int wickets, boolean maiden) {}
    public record WicketEvent(int overNumber, int ballNumber, String dismissal, String dismissedBatter,
                              boolean creditedToBowler) {}
    public record BowlerSpell(int inningsNumber, String battingTeam, UUID bowlerId, String bowlerName,
                              int legalBalls, String overs, int runsConceded, int wickets, double economy,
                              int maidens, int wides, int noBalls, List<OverFigure> oversBreakdown,
                              List<WicketEvent> wicketBreakdown) {}
}
