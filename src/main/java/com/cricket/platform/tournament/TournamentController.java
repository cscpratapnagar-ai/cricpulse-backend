package com.cricket.platform.tournament;

import com.cricket.platform.tournament.dto.request.ChangeTournamentStatusRequest;
import com.cricket.platform.tournament.dto.request.CreateTournamentRequest;
import com.cricket.platform.tournament.dto.request.ScheduleFixtureRequest;
import com.cricket.platform.tournament.dto.response.GenerateFixturesResponse;
import com.cricket.platform.tournament.dto.response.QualificationPreviewResponse;
import com.cricket.platform.tournament.dto.response.TournamentFixtureResponse;
import com.cricket.platform.tournament.dto.response.TournamentPointRowResponse;
import com.cricket.platform.tournament.dto.response.TournamentResponse;
import com.cricket.platform.tournament.dto.response.TournamentTeamResponse;
import com.cricket.platform.tournament.service.TournamentFixtureService;
import com.cricket.platform.tournament.service.TournamentService;
import com.cricket.platform.tournament.service.TournamentStandingsService;
import com.cricket.platform.tournament.service.TournamentStatusService;
import com.cricket.platform.tournament.service.TournamentTeamService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final TournamentStatusService tournamentStatusService;
    private final TournamentTeamService tournamentTeamService;
    private final TournamentFixtureService tournamentFixtureService;
    private final TournamentStandingsService tournamentStandingsService;

    public TournamentController(
            TournamentService tournamentService,
            TournamentStatusService tournamentStatusService,
            TournamentTeamService tournamentTeamService,
            TournamentFixtureService tournamentFixtureService,
            TournamentStandingsService tournamentStandingsService) {
        this.tournamentService = tournamentService;
        this.tournamentStatusService = tournamentStatusService;
        this.tournamentTeamService = tournamentTeamService;
        this.tournamentFixtureService = tournamentFixtureService;
        this.tournamentStandingsService = tournamentStandingsService;
    }

    @GetMapping("/mine")
    public List<TournamentResponse> mine(Authentication authentication) {
        return tournamentService.findMine(authentication);
    }

    @PostMapping
    public TournamentResponse create(
            @Valid @RequestBody CreateTournamentRequest request,
            Authentication authentication) {
        return tournamentService.create(request, authentication);
    }

    @GetMapping("/{id}")
    public TournamentResponse get(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentService.findById(id, authentication);
    }

    @PatchMapping("/{id}/status")
    public TournamentResponse changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeTournamentStatusRequest request,
            Authentication authentication) {
        return tournamentStatusService.changeStatus(id, request, authentication);
    }

    @GetMapping("/{id}/teams")
    public List<TournamentTeamResponse> teams(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentTeamService.findTeams(id, authentication);
    }

    @PostMapping("/{id}/teams/{teamId}")
    public TournamentTeamResponse addTeam(
            @PathVariable UUID id,
            @PathVariable UUID teamId,
            Authentication authentication) {
        return tournamentTeamService.addTeam(id, teamId, authentication);
    }

    @DeleteMapping("/{id}/teams/{teamId}")
    public void removeTeam(
            @PathVariable UUID id,
            @PathVariable UUID teamId,
            Authentication authentication) {
        tournamentTeamService.removeTeam(id, teamId, authentication);
    }

    @PostMapping("/{id}/matches/{matchId}")
    public TournamentFixtureResponse addMatch(
            @PathVariable UUID id,
            @PathVariable UUID matchId,
            @RequestParam(defaultValue = "LEAGUE") String stage,
            Authentication authentication) {
        return tournamentFixtureService.addMatch(id, matchId, stage, authentication);
    }

    @PostMapping("/{id}/fixtures/generate")
    public GenerateFixturesResponse generateFixtures(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentFixtureService.generateFixtures(id, authentication);
    }

    @GetMapping("/{id}/fixtures")
    public List<TournamentFixtureResponse> fixtures(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentFixtureService.findFixtures(id, authentication);
    }

    @PostMapping("/{id}/fixtures/{matchId}/schedule")
    public TournamentFixtureResponse schedule(
            @PathVariable UUID id,
            @PathVariable UUID matchId,
            @Valid @RequestBody ScheduleFixtureRequest request,
            Authentication authentication) {
        return tournamentFixtureService.scheduleFixture(id, matchId, request, authentication);
    }

    @GetMapping("/{id}/points-table")
    public List<TournamentPointRowResponse> points(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentStandingsService.getPointsTable(id, authentication);
    }

    @GetMapping("/{id}/qualification")
    public QualificationPreviewResponse qualification(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentStandingsService.getQualificationPreview(id, authentication);
    }
}
