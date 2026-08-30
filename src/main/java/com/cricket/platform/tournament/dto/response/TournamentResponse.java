package com.cricket.platform.tournament.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record TournamentResponse(
        UUID id,
        String name,
        String format,
        int overs,
        String location,
        LocalDate startDate,
        String status,
        String createdAt
) {
}
