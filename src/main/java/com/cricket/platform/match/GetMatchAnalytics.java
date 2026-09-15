package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class GetMatchAnalytics {
    private final JdbcTemplate jdbc;

    public GetMatchAnalytics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MatchAnalytics get(UUID matchId) {
        List<InningsRow> innings = jdbc.query("""
                SELECT i.id, i.innings_number, bt.name batting_team, i.total_runs, i.wickets,
                       i.legal_balls, i.total_overs, i.target_runs
                FROM innings i
                JOIN teams bt ON bt.id=i.batting_team_id
                WHERE i.match_id=?
                ORDER BY i.innings_number
                """, (rs, row) -> new InningsRow(
                rs.getObject("id", UUID.class), rs.getInt("innings_number"),
                rs.getString("batting_team"), rs.getInt("total_runs"), rs.getInt("wickets"),
                rs.getInt("legal_balls"), rs.getInt("total_overs"),
                (Integer) rs.getObject("target_runs")), matchId);

        if (innings.isEmpty()) throw new IllegalArgumentException("Match has no innings data");

        List<InningsAnalytics> result = innings.stream().map(this::analytics).toList();
        return new MatchAnalytics(matchId, result);
    }

    private InningsAnalytics analytics(InningsRow innings) {
        List<RawOver> raw = jdbc.query("""
                SELECT over_number,
                       COALESCE(SUM(bat_runs + extra_runs),0) runs,
                       COALESCE(SUM(CASE WHEN legal_delivery THEN 1 ELSE 0 END),0) legal_balls,
                       COALESCE(SUM(CASE WHEN legal_delivery AND bat_runs + extra_runs = 0 THEN 1 ELSE 0 END),0) dot_balls,
                       COALESCE(SUM(CASE WHEN wicket_type IS NOT NULL THEN 1 ELSE 0 END),0) wickets,
                       COALESCE(SUM(CASE WHEN extra_type='WIDE' THEN extra_runs ELSE 0 END),0) wides,
                       COALESCE(SUM(CASE WHEN extra_type='NO_BALL' THEN extra_runs ELSE 0 END),0) no_balls,
                       COALESCE(SUM(CASE WHEN bat_runs=4 THEN 1 ELSE 0 END),0) fours,
                       COALESCE(SUM(CASE WHEN bat_runs=6 THEN 1 ELSE 0 END),0) sixes
                FROM delivery_events
                WHERE innings_id=?
                GROUP BY over_number
                ORDER BY over_number
                """, (rs,row) -> new RawOver(
                rs.getInt("over_number"), rs.getInt("runs"), rs.getInt("legal_balls"),
                rs.getInt("dot_balls"), rs.getInt("wickets"), rs.getInt("wides"), rs.getInt("no_balls"),
                rs.getInt("fours"), rs.getInt("sixes")), innings.id());

        List<OverAnalytics> overs = new ArrayList<>(raw.size());
        int cumulativeRuns = 0;
        int cumulativeWickets = 0;
        int cumulativeLegalBalls = 0;
        for (RawOver over : raw) {
            cumulativeRuns += over.runs();
            cumulativeWickets += over.wickets();
            cumulativeLegalBalls += over.legalBalls();
            overs.add(new OverAnalytics(over.overNumber(), over.runs(), over.legalBalls(), over.dotBalls(),
                    over.wickets(), over.wides(), over.noBalls(), over.fours(), over.sixes(), cumulativeRuns,
                    cumulativeWickets, cumulativeLegalBalls, rate(cumulativeRuns, cumulativeLegalBalls)));
        }

        int powerplayEnd = Math.max(1, (int) Math.ceil(innings.totalOvers() * 0.2));
        int deathStart = Math.max(powerplayEnd + 1, (int) Math.floor(innings.totalOvers() * 0.8) + 1);
        PhaseTotals powerplay = phase(overs, 1, powerplayEnd);
        PhaseTotals middle = phase(overs, powerplayEnd + 1, deathStart - 1);
        PhaseTotals death = phase(overs, deathStart, Integer.MAX_VALUE);

        return new InningsAnalytics(innings.inningsNumber(), innings.battingTeam(), innings.totalRuns(),
                innings.wickets(), innings.legalBalls(), innings.totalOvers(), innings.targetRuns(),
                rate(innings.totalRuns(), innings.legalBalls()), boundaryRuns(overs),
                overs, new PhaseAnalytics("POWERPLAY", powerplay), new PhaseAnalytics("MIDDLE", middle),
                new PhaseAnalytics("DEATH", death));
    }

    private PhaseTotals phase(List<OverAnalytics> overs, int from, int to) {
        int runs = 0, wickets = 0, balls = 0, dots = 0, fours = 0, sixes = 0;
        for (OverAnalytics over : overs) {
            if (over.overNumber() >= from && over.overNumber() <= to) {
                runs += over.runs(); wickets += over.wickets(); balls += over.legalBalls(); dots += over.dotBalls();
                fours += over.fours(); sixes += over.sixes();
            }
        }
        return new PhaseTotals(runs, wickets, balls, dots, fours, sixes, rate(runs, balls));
    }

    private int boundaryRuns(List<OverAnalytics> overs) {
        return overs.stream().mapToInt(over -> over.fours() * 4 + over.sixes() * 6).sum();
    }

    private BigDecimal rate(int runs, int balls) {
        if (balls <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(runs * 6.0 / balls).setScale(2, RoundingMode.HALF_UP);
    }

    record InningsRow(UUID id, int inningsNumber, String battingTeam, int totalRuns, int wickets,
                      int legalBalls, int totalOvers, Integer targetRuns) {}

    record RawOver(int overNumber, int runs, int legalBalls, int dotBalls, int wickets, int wides,
                   int noBalls, int fours, int sixes) {}

    public record OverAnalytics(int overNumber, int runs, int legalBalls, int dotBalls, int wickets, int wides,
                                int noBalls, int fours, int sixes, int cumulativeRuns,
                                int cumulativeWickets, int cumulativeLegalBalls, BigDecimal cumulativeRunRate) {}

    public record PhaseTotals(int runs, int wickets, int legalBalls, int dotBalls, int fours, int sixes,
                              BigDecimal runRate) {}

    public record PhaseAnalytics(String phase, PhaseTotals totals) {}

    public record InningsAnalytics(int inningsNumber, String battingTeam, int runs, int wickets,
                                   int legalBalls, int totalOvers, Integer targetRuns,
                                   BigDecimal runRate, int boundaryRuns, List<OverAnalytics> overs,
                                   PhaseAnalytics powerplay, PhaseAnalytics middle,
                                   PhaseAnalytics death) {}

    public record MatchAnalytics(UUID matchId, List<InningsAnalytics> innings) {}
}
