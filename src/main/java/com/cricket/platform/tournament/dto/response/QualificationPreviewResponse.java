package com.cricket.platform.tournament.dto.response;

import java.util.List;

public record QualificationPreviewResponse(
        boolean eligible,
        String message,
        List<TournamentPointRowResponse> table,
        List<QualifierResponse> qualifiers
) {
}
