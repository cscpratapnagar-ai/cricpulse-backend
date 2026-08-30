package com.cricket.platform.tournament.dto.response;

public record QualifierResponse(
        int seed,
        String label,
        TournamentPointRowResponse higherSeed,
        TournamentPointRowResponse lowerSeed
) {
}
