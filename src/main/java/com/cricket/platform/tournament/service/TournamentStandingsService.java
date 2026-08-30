package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.TournamentController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface TournamentStandingsService {
    List<TournamentController.PointRow> getPointsTable(UUID tournamentId, Authentication authentication);
    TournamentController.QualificationPreview getQualificationPreview(UUID tournamentId, Authentication authentication);
}