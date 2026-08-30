package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.TournamentController;
import org.springframework.security.core.Authentication;

import java.util.UUID;

public interface TournamentStatusService {
    TournamentController.TournamentView changeStatus(
            UUID tournamentId,
            TournamentController.StatusRequest request,
            Authentication authentication
    );
}
