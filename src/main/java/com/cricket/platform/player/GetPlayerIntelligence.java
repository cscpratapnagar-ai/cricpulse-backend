package com.cricket.platform.player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class GetPlayerIntelligence {
    private final GetPlayerStatistics statistics;
    private final GetPlayerPerformanceHistory history;

    public GetPlayerIntelligence(GetPlayerStatistics statistics,
                                 GetPlayerPerformanceHistory history) {
        this.statistics = statistics;
        this.history = history;
    }

    public Intelligence get(UUID playerId, int requestedMatches) {
        int limit = Math.max(1, Math.min(requestedMatches, 20));
        GetPlayerStatistics.PlayerStatistics career = statistics.one(playerId);
        List<GetPlayerPerformanceHistory.MatchPerformance> matches = history.recent(playerId, limit);

        int recentRuns = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::runs).sum();
        int recentWickets = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::wickets).sum();
        int recentBalls = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::balls).sum();
        int recentDismissals = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::dismissals).sum();
        int recentBowlingBalls = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::bowlingBalls).sum();
        int recentConceded = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::runsConceded).sum();
        int recentFours = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::fours).sum();
        int recentSixes = matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::sixes).sum();

        BigDecimal recentAverage = recentDismissals == 0
                ? BigDecimal.ZERO
                : decimal(recentRuns / (double) recentDismissals);
        BigDecimal recentStrikeRate = recentBalls == 0
                ? BigDecimal.ZERO
                : decimal(recentRuns * 100.0 / recentBalls);
        BigDecimal recentEconomy = recentBowlingBalls == 0
                ? BigDecimal.ZERO
                : decimal(recentConceded / (recentBowlingBalls / 6.0));

        String form = formLabel(matches);
        return new Intelligence(
                career.playerId(), career.playerName(), matches.size(), form,
                recentRuns, recentAverage, recentStrikeRate, recentFours, recentSixes,
                recentWickets, recentEconomy, career.runs(), career.wickets(),
                matches.isEmpty() ? "No completed match data available" : "Based only on completed matches"
        );
    }

    private String formLabel(List<GetPlayerPerformanceHistory.MatchPerformance> matches) {
        if (matches.isEmpty()) return "NO DATA";
        if (matches.size() < 3) return "EARLY FORM";

        int split = matches.size() / 2;
        List<GetPlayerPerformanceHistory.MatchPerformance> recentHalf = matches.subList(0, split);
        List<GetPlayerPerformanceHistory.MatchPerformance> olderHalf = matches.subList(split, matches.size());
        double recentRunRate = averageRuns(recentHalf);
        double olderRunRate = averageRuns(olderHalf);
        double recentWicketRate = averageWickets(recentHalf);
        double olderWicketRate = averageWickets(olderHalf);

        boolean battingUp = recentRunRate > olderRunRate;
        boolean bowlingUp = recentWicketRate > olderWicketRate;
        boolean battingDown = recentRunRate < olderRunRate;
        boolean bowlingDown = recentWicketRate < olderWicketRate;
        if ((battingUp || bowlingUp) && !(battingDown && bowlingDown)) return "IMPROVING";
        if ((battingDown || bowlingDown) && !(battingUp && bowlingUp)) return "COOLING";
        return "STABLE";
    }

    private double averageRuns(List<GetPlayerPerformanceHistory.MatchPerformance> matches) {
        return matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::runs).average().orElse(0);
    }

    private double averageWickets(List<GetPlayerPerformanceHistory.MatchPerformance> matches) {
        return matches.stream().mapToInt(GetPlayerPerformanceHistory.MatchPerformance::wickets).average().orElse(0);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public record Intelligence(
            UUID playerId,
            String playerName,
            int sampleMatches,
            String form,
            int recentRuns,
            BigDecimal recentAverage,
            BigDecimal recentStrikeRate,
            int recentFours,
            int recentSixes,
            int recentWickets,
            BigDecimal recentEconomy,
            int careerRuns,
            int careerWickets,
            String dataBasis) {}
}
