package com.cricket.platform.tournament;

import com.cricket.platform.tournament.service.TournamentFixtureService;
import com.cricket.platform.tournament.service.TournamentService;
import com.cricket.platform.tournament.service.TournamentStandingsService;
import com.cricket.platform.tournament.service.TournamentStatusService;
import com.cricket.platform.tournament.service.TournamentTeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
    public List<TournamentView> mine(Authentication authentication) {
        return tournamentService.findMine(authentication);
    }

    @PostMapping
    public TournamentView create(
            @Valid @RequestBody CreateRequest request,
            Authentication authentication) {
        return tournamentService.create(request, authentication);
    }

    @GetMapping("/{id}")
    public TournamentView get(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentService.findById(id, authentication);
    }

    @PatchMapping("/{id}/status")
    public TournamentView changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusRequest request,
            Authentication authentication) {
        return tournamentStatusService.changeStatus(id, request, authentication);
    }

    @GetMapping("/{id}/teams")
    public List<TeamView> teams(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentTeamService.findTeams(id, authentication);
    }

    @PostMapping("/{id}/teams/{teamId}")
    public TeamView addTeam(
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
    public FixtureView addMatch(
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
    public List<FixtureView> fixtures(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentFixtureService.findFixtures(id, authentication);
    }

    @PostMapping("/{id}/fixtures/{matchId}/schedule")
    public FixtureView schedule(
            @PathVariable UUID id,
            @PathVariable UUID matchId,
            @Valid @RequestBody ScheduleRequest request,
            Authentication authentication) {
        return tournamentFixtureService.scheduleFixture(id, matchId, request, authentication);
    }

    @GetMapping("/{id}/points-table")
    public List<PointRow> points(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentStandingsService.getPointsTable(id, authentication);
    }

    @GetMapping("/{id}/qualification")
    public QualificationPreview qualification(
            @PathVariable UUID id,
            Authentication authentication) {
        return tournamentStandingsService.getQualificationPreview(id, authentication);
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank String format,
            @Positive int overs,
            String location,
            LocalDate startDate) {
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record ScheduleRequest(@NotNull OffsetDateTime scheduledAt) {
    }

    public record TournamentView(
            UUID id,
            String name,
            String format,
            int overs,
            String location,
            LocalDate startDate,
            String status,
            String createdAt) {
    }

    public record TeamView(
            UUID id,
            String name,
            String city,
            Integer seed) {
    }

    public record FixtureView(
            UUID matchId,
            Integer fixtureNumber,
            String stage,
            String matchName,
            UUID teamAId,
            String teamAName,
            UUID teamBId,
            String teamBName,
            String status,
            String scheduledAt) {
    }

    public record GenerateFixturesResponse(
            UUID tournamentId,
            int generated,
            int skipped,
            int totalPairs,
            List<FixtureView> fixtures) {
    }

    public record PointRow(
            UUID teamId,
            String teamName,
            int played,
            int wins,
            int losses,
            int ties,
            int points,
            int runsFor,
            int runsAgainst,
            double nrr) {
    }

    public record QualificationPreview(
            boolean eligible,
            String message,
            List<PointRow> table,
            List<Qualifier> qualifiers) {
    }

    public record Qualifier(
            int seed,
            String label,
            PointRow higherSeed,
            PointRow lowerSeed) {
    }
}
