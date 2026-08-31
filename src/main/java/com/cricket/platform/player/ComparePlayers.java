package com.cricket.platform.player;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class ComparePlayers {
    private final GetPlayerProfile profiles;
    private final GetPlayerStatistics statistics;

    public ComparePlayers(GetPlayerProfile profiles, GetPlayerStatistics statistics) {
        this.profiles = profiles;
        this.statistics = statistics;
    }

    public Comparison compare(UUID leftId, UUID rightId) {
        if (leftId.equals(rightId)) throw new IllegalArgumentException("Choose two different players");
        return new Comparison(snapshot(leftId), snapshot(rightId));
    }

    private PlayerSnapshot snapshot(UUID id) {
        var profile = profiles.get(id);
        var stats = statistics.one(id);

        String bestBowling = stats.bestWickets() > 0 ? stats.bestWickets() + " wkts" : "—";

        return new PlayerSnapshot(
                id,
                profile.name(),
                profile.playingRole(),
                profile.city(),
                profile.profilePhotoUrl(),
                stats.matches(),
                stats.battingInnings(),
                stats.runs(),
                stats.battingBalls(),
                stats.fours(),
                stats.sixes(),
                stats.battingAverage(),
                stats.strikeRate(),
                stats.wickets(),
                stats.bowlingBalls(),
                stats.runsConceded(),
                stats.economy(),
                String.valueOf(stats.highestScore()),
                bestBowling
        );
    }

    public record Comparison(PlayerSnapshot left, PlayerSnapshot right) {}

    public record PlayerSnapshot(
            UUID playerId,
            String name,
            String role,
            String teamName,
            String profilePhotoUrl,
            int matches,
            int innings,
            int runs,
            int balls,
            int fours,
            int sixes,
            BigDecimal average,
            BigDecimal strikeRate,
            int wickets,
            int bowlingBalls,
            int runsConceded,
            BigDecimal economy,
            String bestScore,
            String bestBowling
    ) {}
}
