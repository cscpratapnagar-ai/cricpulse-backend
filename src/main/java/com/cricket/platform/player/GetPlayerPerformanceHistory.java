package com.cricket.platform.player;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class GetPlayerPerformanceHistory {
    private final JdbcTemplate jdbc;

    public GetPlayerPerformanceHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MatchPerformance> recent(UUID playerId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbc.query("""
                SELECT m.id match_id, m.name match_name, m.format, m.status, m.scheduled_at, m.completed_at,
                       ta.name team_name, tb.name opponent_name,
                       COALESCE(b.runs,0) runs, COALESCE(b.balls,0) balls,
                       COALESCE(b.fours,0) fours, COALESCE(b.sixes,0) sixes,
                       COALESCE(b.dismissals,0) dismissals,
                       COALESCE(w.balls,0) bowling_balls, COALESCE(w.runs_conceded,0) runs_conceded,
                       COALESCE(w.wickets,0) wickets
                FROM matches m
                LEFT JOIN teams ta ON ta.id = (
                    SELECT i.batting_team_id FROM innings i
                    JOIN innings_batters ib ON ib.innings_id=i.id
                    WHERE i.match_id=m.id AND ib.player_id=? LIMIT 1
                )
                LEFT JOIN teams tb ON tb.id = (
                    SELECT CASE WHEN m.team_a_id=ta.id THEN m.team_b_id ELSE m.team_a_id END
                )
                LEFT JOIN (
                    SELECT i.match_id, ib.player_id, SUM(ib.runs) runs, SUM(ib.balls_faced) balls,
                           SUM(ib.fours) fours, SUM(ib.sixes) sixes,
                           SUM(CASE WHEN ib.is_out THEN 1 ELSE 0 END) dismissals
                    FROM innings_batters ib JOIN innings i ON i.id=ib.innings_id
                    GROUP BY i.match_id, ib.player_id
                ) b ON b.match_id=m.id AND b.player_id=?
                LEFT JOIN (
                    SELECT i.match_id, bow.player_id, SUM(bow.legal_balls) balls,
                           SUM(bow.runs_conceded) runs_conceded, SUM(bow.wickets) wickets
                    FROM innings_bowlers bow JOIN innings i ON i.id=bow.innings_id
                    GROUP BY i.match_id, bow.player_id
                ) w ON w.match_id=m.id AND w.player_id=?
                WHERE m.status='COMPLETED' AND (b.player_id IS NOT NULL OR w.player_id IS NOT NULL)
                ORDER BY COALESCE(m.completed_at,m.scheduled_at,m.created_at) DESC
                LIMIT ?
                """, (rs,row)->new MatchPerformance(
                rs.getObject("match_id", UUID.class), rs.getString("match_name"), rs.getString("format"),
                rs.getString("status"), rs.getObject("scheduled_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getString("team_name"),
                rs.getString("opponent_name"), rs.getInt("runs"), rs.getInt("balls"),
                rs.getInt("fours"), rs.getInt("sixes"), rs.getInt("dismissals"),
                rs.getInt("bowling_balls"), rs.getInt("runs_conceded"), rs.getInt("wickets")), playerId, playerId, playerId, safeLimit);
    }

    public PerformanceTrend trend(UUID playerId, int limit) {
        List<MatchPerformance> matches = recent(playerId, limit);
        List<TrendPoint> chronological = matches.reversed().stream().map((m)->new TrendPoint(
                m.matchId(), m.matchName(), m.completedAt()!=null?m.completedAt():m.scheduledAt(),
                m.runs(), m.wickets(),
                m.balls()==0?BigDecimal.ZERO:BigDecimal.valueOf(m.runs()*100.0/m.balls()),
                m.bowlingBalls()==0?BigDecimal.ZERO:BigDecimal.valueOf(m.runsConceded()/(m.bowlingBalls()/6.0))
        )).toList();
        return new PerformanceTrend(chronological);
    }

    public record MatchPerformance(UUID matchId,String matchName,String format,String status,
                                   OffsetDateTime scheduledAt,OffsetDateTime completedAt,
                                   String teamName,String opponentName,int runs,int balls,int fours,int sixes,
                                   int dismissals,int bowlingBalls,int runsConceded,int wickets) {}
    public record TrendPoint(UUID matchId,String matchName,OffsetDateTime playedAt,int runs,int wickets,
                             BigDecimal strikeRate,BigDecimal economy) {}
    public record PerformanceTrend(List<TrendPoint> points) {}
}
