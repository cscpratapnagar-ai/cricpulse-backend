package com.cricket.platform.tournament.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChangeTournamentStatusRequest(
        @NotBlank String status
) {
}
