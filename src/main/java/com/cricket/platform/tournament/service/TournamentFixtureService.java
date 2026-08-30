package com.cricket.platform.tournament.service;

import com.cricket.platform.tournament.TournamentController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface TournamentFixtureService {
    TournamentController.FixtureView addMatch(UUID tournamentId, UUID matchId, String stage, Authentication authentication);
    TournamentController.GenerateFixturesResponse generateFixtures(UUID tournamentId, Authentication authentication);
    List<TournamentController.FixtureView> findFixtures(UUID tournamentId, Authentication authentication);
    TournamentController.FixtureView scheduleFixture(UUID tournamentId, UUID matchId,
                                                     TournamentController.ScheduleRequest request,
                                                     Authentication authentication);
}