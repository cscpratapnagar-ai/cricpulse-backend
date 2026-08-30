package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.TournamentController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface TournamentService {
    List<TournamentController.TournamentView> findMine(Authentication authentication);
    TournamentController.TournamentView findById(UUID tournamentId, Authentication authentication);
}
