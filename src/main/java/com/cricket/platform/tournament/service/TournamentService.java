package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.dto.request.CreateTournamentRequest;
import com.cricket.platform.tournament.dto.response.TournamentResponse;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;

public interface TournamentService {
    TournamentResponse create(CreateTournamentRequest request, Authentication authentication);
    List<TournamentResponse> findMine(Authentication authentication);
    TournamentResponse findById(UUID tournamentId, Authentication authentication);
}
