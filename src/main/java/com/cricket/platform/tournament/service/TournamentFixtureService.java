package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.dto.request.ScheduleFixtureRequest;
import com.cricket.platform.tournament.dto.response.GenerateFixturesResponse;
import com.cricket.platform.tournament.dto.response.TournamentFixtureResponse;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;

public interface TournamentFixtureService {
    TournamentFixtureResponse addMatch(UUID tournamentId, UUID matchId, String stage, Authentication authentication);
    GenerateFixturesResponse generateFixtures(UUID tournamentId, Authentication authentication);
    List<TournamentFixtureResponse> findFixtures(UUID tournamentId, Authentication authentication);
    TournamentFixtureResponse scheduleFixture(UUID tournamentId, UUID matchId, ScheduleFixtureRequest request, Authentication authentication);
}
