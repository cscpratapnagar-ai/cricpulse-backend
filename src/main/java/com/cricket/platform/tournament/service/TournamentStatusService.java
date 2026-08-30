package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.dto.request.ChangeTournamentStatusRequest;
import com.cricket.platform.tournament.dto.response.TournamentResponse;
import org.springframework.security.core.Authentication;
import java.util.UUID;

public interface TournamentStatusService {
    TournamentResponse changeStatus(UUID tournamentId, ChangeTournamentStatusRequest request, Authentication authentication);
}
