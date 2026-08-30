package com.cricket.platform.tournament.dto.response;

import java.util.UUID;

public record TournamentTeamResponse(
        UUID id,
        String name,
        String city,
        Integer seed
) {
}
