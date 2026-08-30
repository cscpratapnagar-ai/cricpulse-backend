package com.cricket.platform.tournament.dto.response;

import java.util.UUID;

public record TournamentPointRowResponse(
        UUID teamId,
        String teamName,
        int played,
        int wins,
        int losses,
        int ties,
        int points,
        int runsFor,
        int runsAgainst,
        double nrr
) {
}
