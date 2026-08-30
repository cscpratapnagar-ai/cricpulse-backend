package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.dto.response.TournamentTeamResponse;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;

public interface TournamentTeamService {
    List<TournamentTeamResponse> findTeams(UUID tournamentId, Authentication authentication);
    TournamentTeamResponse addTeam(UUID tournamentId, UUID teamId, Authentication authentication);
    void removeTeam(UUID tournamentId, UUID teamId, Authentication authentication);
}
