package com.cricket.platform.tournament;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
public class GetTournamentAnalytics {
    private final JdbcTemplate jdbc;
    public GetTournamentAnalytics(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public TournamentAnalytics get(UUID tournamentId) {
        TournamentRow tournament = jdbc.queryForObject("SELECT id,name,format,overs,status FROM tournaments WHERE id=?",(rs,row)->new TournamentRow(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("format"),rs.getInt("overs"),rs.getString("status")),tournamentId);
        List<FixtureRow> fixtures=jdbc.query("SELECT tm.match_id,tm.fixture_number,tm.stage,m.status,m.team_a_id,a.name team_a_name,m.team_b_id,b.name team_b_name FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id JOIN teams a ON a.id=m.team_a_id JOIN teams b ON b.id=m.team_b_id WHERE tm.tournament_id=? ORDER BY COALESCE(tm.fixture_number,999999)",(rs,row)->new FixtureRow(rs.getObject("match_id",UUID.class),rs.getObject("fixture_number",Integer.class),rs.getString("stage"),rs.getString("status"),rs.getObject("team_a_id",UUID.class),rs.getString("team_a_name"),rs.getObject("team_b_id",UUID.class),rs.getString("team_b_name")),tournamentId);
        List<TeamRow> teams=jdbc.query("SELECT t.id,t.name FROM tournament_teams tt JOIN teams t ON t.id=tt.team_id WHERE tt.tournament_id=? ORDER BY COALESCE(tt.seed,999),t.name",(rs,row)->new TeamRow(rs.getObject("id",UUID.class),rs.getString("name")),tournamentId);
        List<CompletedMatch> completed=completedMatches(fixtures);
        List<TeamAnalytics> teamAnalytics=teams.stream().map(t->teamAnalytics(t,completed)).sorted(Comparator.comparingInt(TeamAnalytics::points).reversed().thenComparing(Comparator.comparingDouble(TeamAnalytics::nrr).reversed()).thenComparing(Comparator.comparingInt(TeamAnalytics::runsFor).reversed()).thenComparing(TeamAnalytics::teamName)).toList();
        int total=fixtures.size(),completedCount=(int)fixtures.stream().filter(f->"COMPLETED".equalsIgnoreCase(f.status())).count(),scheduled=(int)fixtures.stream().filter(f->"SCHEDULED".equalsIgnoreCase(f.status())).count(),live=(int)fixtures.stream().filter(f->"LIVE".equalsIgnoreCase(f.status())).count();
        return new TournamentAnalytics(tournamentId,tournament.name(),tournament.format(),tournament.overs(),tournament.status(),total,completedCount,scheduled,live,percentage(completedCount,total),teamAnalytics,playerLeaders(tournamentId,true,5),playerLeaders(tournamentId,false,5),fixtureProgress(fixtures),fieldingLeaders(tournamentId,10),phaseLeaders(tournamentId,true,5),phaseLeaders(tournamentId,false,5),playerPerformances(tournamentId));
    }
    private List<CompletedMatch> completedMatches(List<FixtureRow> fixtures){List<UUID> ids=fixtures.stream().filter(f->"COMPLETED".equalsIgnoreCase(f.status())).map(FixtureRow::matchId).toList();if(ids.isEmpty())return List.of();String p=String.join(",",Collections.nCopies(ids.size(),"?"));return jdbc.query("SELECT m.id match_id,m.team_a_id,m.team_b_id,i1.batting_team_id i1_team,i1.total_runs i1_runs,i1.legal_balls i1_balls,i2.batting_team_id i2_team,i2.total_runs i2_runs,i2.legal_balls i2_balls FROM matches m LEFT JOIN innings i1 ON i1.match_id=m.id AND i1.innings_number=1 LEFT JOIN innings i2 ON i2.match_id=m.id AND i2.innings_number=2 WHERE m.id IN ("+p+")",(rs,row)->new CompletedMatch(rs.getObject("match_id",UUID.class),rs.getObject("team_a_id",UUID.class),rs.getObject("team_b_id",UUID.class),rs.getObject("i1_team",UUID.class),rs.getInt("i1_runs"),rs.getInt("i1_balls"),rs.getObject("i2_team",UUID.class),rs.getInt("i2_runs"),rs.getInt("i2_balls")),ids.toArray());}
    private TeamAnalytics teamAnalytics(TeamRow t,List<CompletedMatch> ms){int p=0,w=0,l=0,ti=0,rf=0,ra=0;double bf=0,ba=0;List<String> o=new ArrayList<>();for(CompletedMatch m:ms){if(!t.id().equals(m.teamA())&&!t.id().equals(m.teamB()))continue;p++;boolean first=t.id().equals(m.i1Team());int own=first?m.i1Runs():m.i2Runs(),opp=first?m.i2Runs():m.i1Runs(),ob=first?m.i1Balls():m.i2Balls(),xb=first?m.i2Balls():m.i1Balls();rf+=own;ra+=opp;bf+=ob;ba+=xb;if(own>opp){w++;o.add("W");}else if(own<opp){l++;o.add("L");}else{ti++;o.add("T");}}return new TeamAnalytics(t.id(),t.name(),p,w,l,ti,w*2+ti,rf,ra,nrr(rf,ra,bf,ba),o.stream().skip(Math.max(0,o.size()-5L)).toList());}
    private List<PlayerLeader> playerLeaders(UUID id, boolean runs, int limit) {
        String sql = runs
                ? """
                  SELECT p.id AS player_id, u.full_name AS player_name,
                         COALESCE(SUM(d.bat_runs), 0) AS runs, 0 AS wickets
                  FROM tournament_matches tm
                  JOIN matches m ON m.id = tm.match_id AND m.status = 'COMPLETED'
                  JOIN innings i ON i.match_id = m.id
                  JOIN delivery_events d ON d.innings_id = i.id
                  JOIN players p ON p.id = d.striker_id
                  JOIN users u ON u.id = p.user_id
                  WHERE tm.tournament_id = ?
                  GROUP BY p.id, u.full_name
                  ORDER BY SUM(d.bat_runs) DESC, u.full_name
                  LIMIT ?
                  """
                : """
                  SELECT p.id AS player_id, u.full_name AS player_name,
                         0 AS runs,
                         COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) AS wickets
                  FROM tournament_matches tm
                  JOIN matches m ON m.id = tm.match_id AND m.status = 'COMPLETED'
                  JOIN innings i ON i.match_id = m.id
                  JOIN delivery_events d ON d.innings_id = i.id
                  JOIN players p ON p.id = d.bowler_id
                  JOIN users u ON u.id = p.user_id
                  WHERE tm.tournament_id = ?
                  GROUP BY p.id, u.full_name
                  HAVING COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) > 0
                  ORDER BY COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) DESC, u.full_name
                  LIMIT ?
                  """;
        return jdbc.query(sql,
                (rs, row) -> new PlayerLeader(
                        rs.getObject("player_id", UUID.class),
                        rs.getString("player_name"),
                        rs.getInt("runs"),
                        rs.getInt("wickets")),
                id, limit);
    }

    private List<TournamentPlayerPerformance> playerPerformances(UUID tournamentId) {
        return jdbc.query("""
                SELECT p.id AS player_id,
                       u.full_name AS player_name,
                       COALESCE(activity.matches, 0) AS matches,
                       COALESCE(b.batting_innings, 0) AS batting_innings,
                       COALESCE(b.runs, 0) AS runs,
                       COALESCE(b.highest_score, 0) AS highest_score,
                       COALESCE(b.dismissals, 0) AS dismissals,
                       COALESCE(b.batting_balls, 0) AS batting_balls,
                       COALESCE(b.fours, 0) AS fours,
                       COALESCE(b.sixes, 0) AS sixes,
                       COALESCE(w.bowling_innings, 0) AS bowling_innings,
                       COALESCE(w.bowling_balls, 0) AS bowling_balls,
                       COALESCE(w.runs_conceded, 0) AS runs_conceded,
                       COALESCE(w.wickets, 0) AS wickets,
                       COALESCE(f.catches, 0) AS catches,
                       COALESCE(f.run_outs, 0) AS run_outs,
                       COALESCE(f.stumpings, 0) AS stumpings
                FROM players p
                JOIN users u ON u.id = p.user_id
                JOIN (
                    SELECT player_match_activity.player_id,
                           COUNT(DISTINCT player_match_activity.match_id) AS matches
                    FROM (
                        SELECT ib.player_id, i.match_id
                        FROM innings_batters ib
                        JOIN innings i ON i.id = ib.innings_id AND i.status = 'COMPLETED'
                        UNION
                        SELECT bow.player_id, i.match_id
                        FROM innings_bowlers bow
                        JOIN innings i ON i.id = bow.innings_id AND i.status = 'COMPLETED'
                        UNION
                        SELECT d.wicket_fielder_id AS player_id, i.match_id
                        FROM deliveries d
                        JOIN innings i ON i.id = d.innings_id AND i.status = 'COMPLETED'
                        WHERE d.wicket_fielder_id IS NOT NULL
                    ) player_match_activity
                    JOIN tournament_matches tm ON tm.match_id = player_match_activity.match_id
                    JOIN matches activity_match ON activity_match.id = player_match_activity.match_id
                                                  AND activity_match.status = 'COMPLETED'
                    WHERE tm.tournament_id = ?
                    GROUP BY player_match_activity.player_id
                ) activity ON activity.player_id = p.id
                LEFT JOIN (
                    SELECT ib.player_id,
                           COUNT(DISTINCT ib.innings_id) AS batting_innings,
                           SUM(ib.runs) AS runs,
                           MAX(ib.runs) AS highest_score,
                           SUM(CASE WHEN ib.is_out THEN 1 ELSE 0 END) AS dismissals,
                           SUM(ib.balls_faced) AS batting_balls,
                           SUM(ib.fours) AS fours,
                           SUM(ib.sixes) AS sixes
                    FROM innings_batters ib
                    JOIN innings i ON i.id = ib.innings_id AND i.status = 'COMPLETED'
                    JOIN tournament_matches tm ON tm.match_id = i.match_id
                    JOIN matches m ON m.id = i.match_id AND m.status = 'COMPLETED'
                    WHERE tm.tournament_id = ?
                    GROUP BY ib.player_id
                ) b ON b.player_id = p.id
                LEFT JOIN (
                    SELECT bow.player_id,
                           COUNT(DISTINCT bow.innings_id) AS bowling_innings,
                           SUM(bow.legal_balls) AS bowling_balls,
                           SUM(bow.runs_conceded) AS runs_conceded,
                           SUM(bow.wickets) AS wickets
                    FROM innings_bowlers bow
                    JOIN innings i ON i.id = bow.innings_id AND i.status = 'COMPLETED'
                    JOIN tournament_matches tm ON tm.match_id = i.match_id
                    JOIN matches m ON m.id = i.match_id AND m.status = 'COMPLETED'
                    WHERE tm.tournament_id = ?
                    GROUP BY bow.player_id
                ) w ON w.player_id = p.id
                LEFT JOIN (
                    SELECT d.wicket_fielder_id AS player_id,
                           COUNT(*) FILTER (WHERE d.wicket_type = 'CAUGHT') AS catches,
                           COUNT(*) FILTER (WHERE d.wicket_type = 'RUN_OUT') AS run_outs,
                           COUNT(*) FILTER (WHERE d.wicket_type = 'STUMPED') AS stumpings
                    FROM deliveries d
                    JOIN innings i ON i.id = d.innings_id AND i.status = 'COMPLETED'
                    JOIN tournament_matches tm ON tm.match_id = i.match_id
                    JOIN matches m ON m.id = i.match_id AND m.status = 'COMPLETED'
                    WHERE tm.tournament_id = ?
                      AND d.wicket_fielder_id IS NOT NULL
                      AND d.wicket_type IN ('CAUGHT', 'RUN_OUT', 'STUMPED')
                    GROUP BY d.wicket_fielder_id
                ) f ON f.player_id = p.id
                ORDER BY COALESCE(b.runs, 0) DESC,
                         COALESCE(w.wickets, 0) DESC,
                         (COALESCE(f.catches, 0) + COALESCE(f.run_outs, 0) + COALESCE(f.stumpings, 0)) DESC,
                         u.full_name
                """, (rs, row) -> {
            int runs = rs.getInt("runs");
            int dismissals = rs.getInt("dismissals");
            int battingBalls = rs.getInt("batting_balls");
            int bowlingBalls = rs.getInt("bowling_balls");
            int runsConceded = rs.getInt("runs_conceded");
            int wickets = rs.getInt("wickets");
            int catches = rs.getInt("catches");
            int runOuts = rs.getInt("run_outs");
            int stumpings = rs.getInt("stumpings");
            return new TournamentPlayerPerformance(
                    rs.getObject("player_id", UUID.class), rs.getString("player_name"),
                    rs.getInt("matches"), rs.getInt("batting_innings"), runs,
                    rs.getInt("highest_score"), dismissals, battingBalls,
                    ratio(dismissals == 0 ? 0 : runs / (double) dismissals),
                    ratio(battingBalls == 0 ? 0 : runs * 100.0 / battingBalls),
                    rs.getInt("bowling_innings"), bowlingBalls, runsConceded, wickets,
                    ratio(wickets == 0 ? 0 : runsConceded / (double) wickets),
                    ratio(bowlingBalls == 0 ? 0 : runsConceded / (bowlingBalls / 6.0)),
                    ratio(wickets == 0 ? 0 : bowlingBalls / (double) wickets),
                    catches, runOuts, stumpings, catches + runOuts + stumpings
            );
        }, tournamentId, tournamentId, tournamentId, tournamentId);
    }

    private static BigDecimal ratio(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private List<FieldingLeader> fieldingLeaders(UUID id,int limit) {
        return jdbc.query("""
                SELECT p.id AS player_id,
                       u.full_name AS player_name,
                       COUNT(*) FILTER (WHERE d.wicket_type = 'CAUGHT') AS catches,
                       COUNT(*) FILTER (WHERE d.wicket_type = 'RUN_OUT') AS run_outs,
                       COUNT(*) FILTER (WHERE d.wicket_type = 'STUMPED') AS stumpings
                FROM tournament_matches tm
                JOIN matches m ON m.id = tm.match_id AND m.status = 'COMPLETED'
                JOIN innings i ON i.match_id = m.id
                JOIN deliveries d ON d.innings_id = i.id
                JOIN players p ON p.id = d.wicket_fielder_id
                JOIN users u ON u.id = p.user_id
                WHERE tm.tournament_id = ?
                  AND d.wicket_fielder_id IS NOT NULL
                  AND d.wicket_type IN ('CAUGHT', 'RUN_OUT', 'STUMPED')
                GROUP BY p.id, u.full_name
                ORDER BY (COUNT(*) FILTER (WHERE d.wicket_type = 'CAUGHT')
                        + COUNT(*) FILTER (WHERE d.wicket_type = 'RUN_OUT')
                        + COUNT(*) FILTER (WHERE d.wicket_type = 'STUMPED')) DESC,
                         u.full_name
                LIMIT ?
                """, (rs, row) -> {
            int catches = rs.getInt("catches");
            int runOuts = rs.getInt("run_outs");
            int stumpings = rs.getInt("stumpings");
            return new FieldingLeader(
                    rs.getObject("player_id", UUID.class),
                    rs.getString("player_name"),
                    catches,
                    runOuts,
                    stumpings,
                    catches + runOuts + stumpings
            );
        }, id, limit);
    }
    private List<PhaseLeader> phaseLeaders(UUID id, boolean batting, int limit) {
        String sql = batting
                ? """
                  SELECT p.id AS player_id, u.full_name AS player_name,
                         CASE
                           WHEN d.over_number < GREATEST(1, CAST(i.total_overs * 0.2 AS INT)) THEN 'POWERPLAY'
                           WHEN d.over_number < GREATEST(1, CAST(i.total_overs * 0.8 AS INT)) THEN 'MIDDLE'
                           ELSE 'DEATH'
                         END AS phase,
                         COALESCE(SUM(d.bat_runs), 0) AS runs,
                         COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) AS wickets
                  FROM tournament_matches tm
                  JOIN matches m ON m.id = tm.match_id AND m.status = 'COMPLETED'
                  JOIN innings i ON i.match_id = m.id
                  JOIN delivery_events d ON d.innings_id = i.id
                  JOIN players p ON p.id = d.striker_id
                  JOIN users u ON u.id = p.user_id
                  WHERE tm.tournament_id = ?
                  GROUP BY p.id, u.full_name, phase
                  ORDER BY runs DESC, u.full_name
                  LIMIT ?
                  """
                : """
                  SELECT p.id AS player_id, u.full_name AS player_name,
                         CASE
                           WHEN d.over_number < GREATEST(1, CAST(i.total_overs * 0.2 AS INT)) THEN 'POWERPLAY'
                           WHEN d.over_number < GREATEST(1, CAST(i.total_overs * 0.8 AS INT)) THEN 'MIDDLE'
                           ELSE 'DEATH'
                         END AS phase,
                         COALESCE(SUM(d.bat_runs), 0) AS runs,
                         COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) AS wickets
                  FROM tournament_matches tm
                  JOIN matches m ON m.id = tm.match_id AND m.status = 'COMPLETED'
                  JOIN innings i ON i.match_id = m.id
                  JOIN delivery_events d ON d.innings_id = i.id
                  JOIN players p ON p.id = d.bowler_id
                  JOIN users u ON u.id = p.user_id
                  WHERE tm.tournament_id = ?
                  GROUP BY p.id, u.full_name, phase
                  ORDER BY wickets DESC, u.full_name
                  LIMIT ?
                  """;
        return jdbc.query(sql,
                (rs, row) -> new PhaseLeader(
                        rs.getObject("player_id", UUID.class),
                        rs.getString("player_name"),
                        rs.getString("phase"),
                        rs.getInt("runs"),
                        rs.getInt("wickets")),
                id, limit);
    }
    private List<FixtureProgress> fixtureProgress(List<FixtureRow> fs){return fs.stream().map(f->new FixtureProgress(f.fixtureNumber(),f.stage(),f.status(),f.teamAName(),f.teamBName())).toList();}
    private static BigDecimal percentage(int v,int t){return t<=0?BigDecimal.ZERO.setScale(1):BigDecimal.valueOf(v*100.0/t).setScale(1,RoundingMode.HALF_UP);}
    private static double nrr(int rf,int ra,double bf,double ba){if(bf<=0||ba<=0)return 0;return BigDecimal.valueOf(rf/(bf/6.0)-ra/(ba/6.0)).setScale(3,RoundingMode.HALF_UP).doubleValue();}
    record TournamentRow(UUID id,String name,String format,int overs,String status){} record TeamRow(UUID id,String name){} record FixtureRow(UUID matchId,Integer fixtureNumber,String stage,String status,UUID teamA,String teamAName,UUID teamB,String teamBName){} record CompletedMatch(UUID matchId,UUID teamA,UUID teamB,UUID i1Team,int i1Runs,int i1Balls,UUID i2Team,int i2Runs,int i2Balls){}
    public record TournamentAnalytics(UUID tournamentId,String tournamentName,String format,int overs,String status,int totalFixtures,int completedFixtures,int scheduledFixtures,int liveFixtures,BigDecimal completionPercentage,List<TeamAnalytics> teams,List<PlayerLeader> topRunScorers,List<PlayerLeader> topWicketTakers,List<FixtureProgress> fixtures,List<FieldingLeader> topFielders,List<PhaseLeader> topBattingPhaseLeaders,List<PhaseLeader> topBowlingPhaseLeaders,List<TournamentPlayerPerformance> playerPerformances){}
    public record TournamentPlayerPerformance(UUID playerId,String playerName,int matches,int battingInnings,int runs,int highestScore,int dismissals,int battingBalls,BigDecimal battingAverage,BigDecimal strikeRate,int bowlingInnings,int bowlingBalls,int runsConceded,int wickets,BigDecimal bowlingAverage,BigDecimal economy,BigDecimal bowlingStrikeRate,int catches,int runOuts,int stumpings,int totalFieldingContributions){}
    public record TeamAnalytics(UUID teamId,String teamName,int played,int wins,int losses,int ties,int points,int runsFor,int runsAgainst,double nrr,List<String> recentForm){}
    public record PlayerLeader(UUID playerId,String playerName,int runs,int wickets){}
    public record FieldingLeader(UUID playerId,String playerName,int catches,int runOuts,int stumpings,int totalContributions){}
    public record PhaseLeader(UUID playerId,String playerName,String phase,int runs,int wickets){}
    public record FixtureProgress(Integer fixtureNumber,String stage,String status,String teamAName,String teamBName){}
}
