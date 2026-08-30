package com.cricket.platform.tournament.dto.response;

import java.util.UUID;

public record TournamentFixtureResponse(
        UUID matchId,
        Integer fixtureNumber,
        String stage,
        String matchName,
        UUID teamAId,
        String teamAName,
        UUID teamBId,
        String teamBName,
        String status,
        String scheduledAt
) {
}
