package com.cricket.platform.tournament.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record CreateTournamentRequest(
        @NotBlank String name,
        @NotBlank String format,
        @Positive int overs,
        String location,
        LocalDate startDate
) {
}
