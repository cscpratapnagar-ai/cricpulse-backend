package com.cricket.platform.tournament;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Default limited-overs tournament standings rules. League fixtures only are
 * supplied by callers; knockout results never feed back into the league table.
 */
final class TournamentStandingsCalculator {
    private TournamentStandingsCalculator() {}

    static List<Standing> calculate(List<Team> teams, List<Match> matches) {
        Map<UUID, Accumulator> rows = new LinkedHashMap<>();
        teams.forEach(team -> rows.put(team.id(), new Accumulator(team)));

        for (Match match : matches) {
            if (!"COMPLETED".equalsIgnoreCase(match.status())
                    || match.teamAId() == null || match.teamBId() == null
                    || match.teamAId().equals(match.teamBId())) continue;
            Accumulator a = rows.get(match.teamAId());
            Accumulator b = rows.get(match.teamBId());
            if (a == null || b == null) continue;

            String result = normalize(match.resultType());
            a.played++;
            b.played++;

            if ("NO_RESULT".equals(result) || "ABANDONED".equals(result)) {
                a.noResults++; b.noResults++;
                a.points++; b.points++;
                a.form.add("NR"); b.form.add("NR");
                continue;
            }
            if ("DRAW".equals(result)) {
                a.ties++; b.ties++;
                a.points++; b.points++;
                a.form.add("D"); b.form.add("D");
                continue;
            }

            if ("TIE".equals(result)) {
                a.ties++; b.ties++;
                a.points++; b.points++;
                a.form.add("T"); b.form.add("T");
            } else {
                UUID winner = match.winnerTeamId();
                if (winner == null) winner = scoreWinner(match);
                if (match.teamAId().equals(winner)) {
                    a.wins++; b.losses++; a.points += 2;
                    a.form.add("W"); b.form.add("L");
                } else if (match.teamBId().equals(winner)) {
                    b.wins++; a.losses++; b.points += 2;
                    b.form.add("W"); a.form.add("L");
                } else {
                    // A completed match without a valid result must not invent points.
                    a.played--; b.played--;
                    continue;
                }
            }
            addNetRunRate(a, b, match);
        }

        return rows.values().stream().map(Accumulator::toStanding)
                .sorted(Comparator.comparingInt(Standing::points).reversed()
                        .thenComparing(Comparator.comparingDouble(Standing::nrr).reversed())
                        .thenComparing(Comparator.comparingInt(Standing::runsFor).reversed())
                        .thenComparing(Standing::teamName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static UUID scoreWinner(Match match) {
        if (match.firstInnings() == null || match.secondInnings() == null) return null;
        if (match.firstInnings().runs() > match.secondInnings().runs())
            return match.firstInnings().battingTeamId();
        if (match.secondInnings().runs() > match.firstInnings().runs())
            return match.secondInnings().battingTeamId();
        return null;
    }

    private static void addNetRunRate(Accumulator a, Accumulator b, Match match) {
        Innings first = match.firstInnings();
        Innings second = match.secondInnings();
        if (first == null || second == null
                || first.battingTeamId() == null || second.battingTeamId() == null
                || first.battingTeamId().equals(second.battingTeamId())) return;
        Accumulator firstTeam = first.battingTeamId().equals(a.team.id()) ? a
                : first.battingTeamId().equals(b.team.id()) ? b : null;
        Accumulator secondTeam = second.battingTeamId().equals(a.team.id()) ? a
                : second.battingTeamId().equals(b.team.id()) ? b : null;
        if (firstTeam == null || secondTeam == null || firstTeam == secondTeam) return;

        double firstBalls = effectiveBalls(first, match.totalOvers());
        double secondBalls = effectiveBalls(second, match.totalOvers());
        if (firstBalls <= 0 || secondBalls <= 0) return;

        firstTeam.runsFor += first.runs();
        firstTeam.runsAgainst += second.runs();
        firstTeam.ballsFor += firstBalls;
        firstTeam.ballsAgainst += secondBalls;
        secondTeam.runsFor += second.runs();
        secondTeam.runsAgainst += first.runs();
        secondTeam.ballsFor += secondBalls;
        secondTeam.ballsAgainst += firstBalls;
    }

    private static double effectiveBalls(Innings innings, Integer matchOvers) {
        int quota = innings.totalOvers() != null && innings.totalOvers() > 0
                ? innings.totalOvers() : matchOvers == null ? 0 : matchOvers;
        if (innings.wickets() >= 10 && quota > 0) return quota * 6.0;
        return Math.max(0, innings.legalBalls());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    record Team(UUID id, String name) {}
    record Innings(UUID battingTeamId, int runs, int wickets, int legalBalls, Integer totalOvers) {}
    record Match(UUID teamAId, UUID teamBId, String status, String resultType,
                 UUID winnerTeamId, Integer totalOvers, Innings firstInnings, Innings secondInnings) {}
    record Standing(UUID teamId, String teamName, int played, int wins, int losses,
                    int ties, int noResults, int points, int runsFor, int runsAgainst,
                    double nrr, List<String> recentForm) {}

    private static final class Accumulator {
        private final Team team;
        private int played, wins, losses, ties, noResults, points, runsFor, runsAgainst;
        private double ballsFor, ballsAgainst;
        private final List<String> form = new ArrayList<>();
        private Accumulator(Team team) { this.team = team; }
        private Standing toStanding() {
            double value = ballsFor <= 0 || ballsAgainst <= 0 ? 0
                    : runsFor / (ballsFor / 6.0) - runsAgainst / (ballsAgainst / 6.0);
            double rounded = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
            return new Standing(team.id(), team.name(), played, wins, losses, ties, noResults,
                    points, runsFor, runsAgainst, rounded,
                    form.stream().skip(Math.max(0, form.size() - 5L)).toList());
        }
    }
}
