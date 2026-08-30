package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.TournamentController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface TournamentTeamService {

    List<TournamentController.TeamView> findTeams(
            UUID tournamentId,
            Authentication authentication
    );

    TournamentController.TeamView addTeam(
            UUID tournamentId,
            UUID teamId,
            Authentication authentication
    );

    void removeTeam(
            UUID tournamentId,
            UUID teamId,
            Authentication authentication
    );
}
