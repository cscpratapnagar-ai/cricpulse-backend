package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.dto.response.QualificationPreviewResponse;
import com.cricket.platform.tournament.dto.response.TournamentPointRowResponse;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;

public interface TournamentStandingsService {
    List<TournamentPointRowResponse> getPointsTable(UUID tournamentId, Authentication authentication);
    QualificationPreviewResponse getQualificationPreview(UUID tournamentId, Authentication authentication);
}
