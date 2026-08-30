package com.cricket.platform.tournament.dto.response;

import java.util.List;
import java.util.UUID;

public record GenerateFixturesResponse(
        UUID tournamentId,
        int generated,
        int skipped,
        int totalPairs,
        List<TournamentFixtureResponse> fixtures
) {
}
