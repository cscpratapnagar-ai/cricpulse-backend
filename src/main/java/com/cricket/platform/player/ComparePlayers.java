package com.cricket.platform.player;

import org.springframework.stereotype.Component;

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
        return new PlayerSnapshot(id, profile.name(), profile.role(), profile.teamName(), profile.profilePhotoUrl(),
                stats.matches(), stats.innings(), stats.runs(), stats.balls(), stats.fours(), stats.sixes(),
                stats.average(), stats.strikeRate(), stats.wickets(), stats.bowlingBalls(), stats.runsConceded(),
                stats.economy(), stats.bestScore(), stats.bestBowling());
    }

    public record Comparison(PlayerSnapshot left, PlayerSnapshot right) {}
    public record PlayerSnapshot(UUID playerId, String name, String role, String teamName, String profilePhotoUrl,
                                 int matches, int innings, int runs, int balls, int fours, int sixes,
                                 double average, double strikeRate, int wickets, int bowlingBalls,
                                 int runsConceded, double economy, String bestScore, String bestBowling) {}
}
