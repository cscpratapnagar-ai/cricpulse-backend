package com.cricket.platform.tournament;

import jakarta.validation.Valid;
import com.cricket.platform.tournament.service.TournamentService;
import com.cricket.platform.tournament.service.TournamentStatusService;
import com.cricket.platform.tournament.service.TournamentTeamService;
import com.cricket.platform.tournament.service.TournamentFixtureService;
import com.cricket.platform.tournament.service.TournamentStandingsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {
 private final TournamentService tournamentService; private final TournamentStatusService tournamentStatusService; private final TournamentTeamService tournamentTeamService; private final TournamentFixtureService tournamentFixtureService; private final TournamentStandingsService tournamentStandingsService;
 public TournamentController(TournamentService tournamentService,TournamentStatusService tournamentStatusService,TournamentTeamService tournamentTeamService,TournamentFixtureService tournamentFixtureService,TournamentStandingsService tournamentStandingsService){this.tournamentService=tournamentService;this.tournamentStatusService=tournamentStatusService;this.tournamentTeamService=tournamentTeamService;this.tournamentFixtureService=tournamentFixtureService;this.tournamentStandingsService=tournamentStandingsService;}
 @GetMapping("/mine") List<TournamentView> mine(Authentication a){return tournamentService.findMine(a);}
 @PostMapping TournamentView create(@Valid @RequestBody CreateRequest q,Authentication a){return tournamentService.create(q,a);}
 @GetMapping("/{id}") TournamentView get(@PathVariable UUID id,Authentication a){return tournamentService.findById(id,a);}
 @PatchMapping("/{id}/status") TournamentView changeStatus(@PathVariable UUID id,@Valid @RequestBody StatusRequest q,Authentication a){return tournamentStatusService.changeStatus(id,q,a);}
 @GetMapping("/{id}/teams") List<TeamView> teams(@PathVariable UUID id,Authentication a){return tournamentTeamService.findTeams(id,a);}
 @PostMapping("/{id}/teams/{teamId}") TeamView addTeam(@PathVariable UUID id,@PathVariable UUID teamId,Authentication a){return tournamentTeamService.addTeam(id,teamId,a);}
 @DeleteMapping("/{id}/teams/{teamId}") void removeTeam(@PathVariable UUID id,@PathVariable UUID teamId,Authentication a){tournamentTeamService.removeTeam(id,teamId,a);}
 @PostMapping("/{id}/matches/{matchId}") TournamentController.FixtureView addMatch(@PathVariable UUID id,@PathVariable UUID matchId,@RequestParam(defaultValue="LEAGUE") String stage,Authentication a){return tournamentFixtureService.addMatch(id,matchId,stage,a);}
 @PostMapping("/{id}/fixtures/generate") TournamentController.GenerateFixturesResponse generateFixtures(@PathVariable UUID id,Authentication a){return tournamentFixtureService.generateFixtures(id,a);}
 @GetMapping("/{id}/fixtures") List<TournamentController.FixtureView> fixtures(@PathVariable UUID id,Authentication a){return tournamentFixtureService.findFixtures(id,a);}
 @PostMapping("/{id}/fixtures/{matchId}/schedule") TournamentController.FixtureView schedule(@PathVariable UUID id,@PathVariable UUID matchId,@Valid @RequestBody ScheduleRequest q,Authentication a){return tournamentFixtureService.scheduleFixture(id,matchId,q,a);}
 @GetMapping("/{id}/points-table") List<PointRow> points(@PathVariable UUID id,Authentication a){return tournamentStandingsService.getPointsTable(id,a);}
 @GetMapping("/{id}/qualification") QualificationPreview qualification(@PathVariable UUID id,Authentication a){return tournamentStandingsService.getQualificationPreview(id,a);}
 public record CreateRequest(@NotBlank String name,@NotBlank String format,@Positive int overs,String location,LocalDate startDate){}
 public record StatusRequest(@NotBlank String status){}
 public record ScheduleRequest(@jakarta.validation.constraints.NotNull OffsetDateTime scheduledAt){}
 public record TournamentView(UUID id,String name,String format,int overs,String location,LocalDate startDate,String status,String createdAt){}
 public record TeamView(UUID id,String name,String city,Integer seed){}
 public record FixtureView(UUID matchId,Integer fixtureNumber,String stage,String matchName,UUID teamAId,String teamAName,UUID teamBId,String teamBName,String status,String scheduledAt){}
 public record GenerateFixturesResponse(UUID tournamentId,int generated,int skipped,int totalPairs,List<FixtureView> fixtures){}
 public record PointRow(UUID teamId,String teamName,int played,int wins,int losses,int ties,int points,int runsFor,int runsAgainst,double nrr){}
 public record QualificationPreview(boolean eligible,String message,List<PointRow> table,List<Qualifier> qualifiers){}
 public record Qualifier(int seed,String label,PointRow higherSeed,PointRow lowerSeed){}
}
