package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Component
public class GetMatchIntelligence {
    private final JdbcTemplate jdbc;

    public GetMatchIntelligence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MatchIntelligence get(UUID matchId) {
        List<InningsData> innings = jdbc.query("""
                SELECT i.id, i.innings_number, i.batting_team_id, bt.name batting_team,
                       i.total_runs, i.wickets, i.legal_balls, i.total_overs,
                       i.target_runs, i.status
                FROM innings i
                JOIN teams bt ON bt.id=i.batting_team_id
                WHERE i.match_id=?
                ORDER BY i.innings_number
                """, (rs, row) -> new InningsData(
                rs.getObject("id", UUID.class), rs.getInt("innings_number"),
                rs.getObject("batting_team_id", UUID.class), rs.getString("batting_team"),
                rs.getInt("total_runs"), rs.getInt("wickets"), rs.getInt("legal_balls"),
                rs.getInt("total_overs"), (Integer) rs.getObject("target_runs"), rs.getString("status")), matchId);

        if (innings.isEmpty()) {
            throw new IllegalArgumentException("Match has no innings data");
        }

        InningsData current = innings.get(innings.size() - 1);
        RecentData recent = jdbc.query("""
                SELECT COALESCE(SUM(bat_runs + extra_runs),0) runs,
                       COUNT(*) deliveries,
                       COALESCE(SUM(CASE WHEN bat_runs + extra_runs=0 THEN 1 ELSE 0 END),0) dots,
                       COALESCE(SUM(CASE WHEN bat_runs=4 THEN 1 ELSE 0 END),0) fours,
                       COALESCE(SUM(CASE WHEN bat_runs=6 THEN 1 ELSE 0 END),0) sixes,
                       COALESCE(SUM(CASE WHEN wicket_type IS NOT NULL THEN 1 ELSE 0 END),0) wickets
                FROM (SELECT bat_runs, extra_runs, wicket_type
                      FROM delivery_events WHERE innings_id=? ORDER BY sequence_no DESC LIMIT 12) d
                """, (rs,row)->new RecentData(rs.getInt("runs"),rs.getInt("deliveries"),rs.getInt("dots"),
                        rs.getInt("fours"),rs.getInt("sixes"),rs.getInt("wickets")), current.id()).stream().findFirst().orElse(new RecentData(0,0,0,0,0,0));

        BigDecimal inningsRate = rate(current.totalRuns(), current.legalBalls());
        BigDecimal recentRate = rate(recent.runs(), recent.deliveries());
        String momentum = momentum(recentRate, inningsRate, recent.wickets());

        Integer target = current.targetRuns();
        Integer requiredRuns = null;
        Integer ballsRemaining = null;
        BigDecimal requiredRate = null;
        String chasePressure = "NOT A CHASE";
        if (target != null && target > current.totalRuns()) {
            requiredRuns = target - current.totalRuns();
            ballsRemaining = Math.max(0, current.totalOvers() * 6 - current.legalBalls());
            requiredRate = ballsRemaining == 0 ? BigDecimal.ZERO : rate(requiredRuns, ballsRemaining);
            chasePressure = pressure(requiredRate, recentRate, current.wickets(), ballsRemaining);
        }

        return new MatchIntelligence(matchId, current.inningsNumber(), current.battingTeam(), current.status(),
                current.totalRuns(), current.wickets(), current.legalBalls(), current.totalOvers(), target,
                inningsRate, recent.runs(), recent.deliveries(), recent.dots(), recent.fours(), recent.sixes(),
                recent.wickets(), recentRate, momentum, requiredRuns, ballsRemaining, requiredRate, chasePressure);
    }

    private BigDecimal rate(int runs, int balls) {
        if (balls <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(runs * 6.0 / balls).setScale(2, RoundingMode.HALF_UP);
    }

    private String momentum(BigDecimal recent, BigDecimal overall, int recentWickets) {
        if (recentWickets >= 2) return "FALLING";
        if (recent.compareTo(overall.add(BigDecimal.valueOf(1.5))) >= 0) return "RISING";
        if (recent.compareTo(overall.subtract(BigDecimal.valueOf(1.5))) <= 0) return "FALLING";
        return "STABLE";
    }

    private String pressure(BigDecimal required, BigDecimal recent, int wickets, int ballsRemaining) {
        if (ballsRemaining <= 6 || wickets >= 8) return "HIGH";
        if (required.compareTo(recent.add(BigDecimal.valueOf(2))) > 0) return "HIGH";
        if (required.compareTo(recent.add(BigDecimal.valueOf(0.5))) > 0) return "MEDIUM";
        return "LOW";
    }

    record InningsData(UUID id,int inningsNumber,UUID battingTeamId,String battingTeam,int totalRuns,
                       int wickets,int legalBalls,int totalOvers,Integer targetRuns,String status) {}
    record RecentData(int runs,int deliveries,int dots,int fours,int sixes,int wickets) {}

    public record MatchIntelligence(UUID matchId,int inningsNumber,String battingTeam,String status,
                                    int runs,int wickets,int legalBalls,int totalOvers,Integer targetRuns,
                                    BigDecimal inningsRunRate,int recentRuns,int recentDeliveries,int recentDots,
                                    int recentFours,int recentSixes,int recentWickets,BigDecimal recentRunRate,
                                    String momentum,Integer requiredRuns,Integer ballsRemaining,BigDecimal requiredRate,
                                    String chasePressure) {}
}
