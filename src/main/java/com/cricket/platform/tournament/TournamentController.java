package com.cricket.platform.tournament;

import jakarta.validation.Valid;
import com.cricket.platform.tournament.service.TournamentService;
import com.cricket.platform.tournament.service.TournamentStatusService;
import com.cricket.platform.tournament.service.TournamentTeamService;
import com.cricket.platform.tournament.service.TournamentFixtureService;
import com.cricket.platform.tournament.service.TournamentStandingsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {
 private final JdbcTemplate jdbc; private final TournamentService tournamentService; private final TournamentStatusService tournamentStatusService; private final TournamentTeamService tournamentTeamService; private final TournamentFixtureService tournamentFixtureService; private final TournamentStandingsService tournamentStandingsService; public TournamentController(JdbcTemplate jdbc,TournamentService tournamentService,TournamentStatusService tournamentStatusService,TournamentTeamService tournamentTeamService,TournamentFixtureService tournamentFixtureService,TournamentStandingsService tournamentStandingsService){this.jdbc=jdbc;this.tournamentService=tournamentService;this.tournamentStatusService=tournamentStatusService;this.tournamentTeamService=tournamentTeamService;this.tournamentFixtureService=tournamentFixtureService;this.tournamentStandingsService=tournamentStandingsService;}
 @GetMapping("/mine") List<TournamentView> mine(Authentication a){return tournamentService.findMine(a);}
 @PostMapping TournamentView create(@Valid @RequestBody CreateRequest q,Authentication a){UUID o=ownerId(a),id=UUID.randomUUID();jdbc.update("INSERT INTO tournaments(id,name,format,overs,location,start_date,status,owner_id) VALUES (?,?,?,?,?,?,?,?)",id,q.name().trim(),q.format().trim().toUpperCase(Locale.ROOT),q.overs(),blank(q.location()),q.startDate(),"DRAFT",o);return get(id,a);}
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
  private FixtureView fixture(UUID tid,UUID mid){return jdbc.queryForObject("SELECT tm.match_id,tm.fixture_number,tm.stage,m.name,m.team_a_id,x.name team_a_name,m.team_b_id,y.name team_b_name,m.status,m.scheduled_at FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id JOIN teams x ON x.id=m.team_a_id JOIN teams y ON y.id=m.team_b_id WHERE tm.tournament_id=? AND tm.match_id=?",(r,n)->new FixtureView(r.getObject("match_id",UUID.class),r.getObject("fixture_number",Integer.class),r.getString("stage"),r.getString("name"),r.getObject("team_a_id",UUID.class),r.getString("team_a_name"),r.getObject("team_b_id",UUID.class),r.getString("team_b_name"),r.getString("status"),r.getObject("scheduled_at")==null?null:r.getObject("scheduled_at").toString()),tid,mid);}
 private void validateTransition(String current,String target,UUID id){if(current==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tournament not found");if(current.equals(target))throw new ResponseStatusException(HttpStatus.CONFLICT,"Tournament is already "+target);if("DRAFT".equals(current)&&"ACTIVE".equals(target)){Integer teamCount=jdbc.queryForObject("SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=?",Integer.class,id);Integer fixtureCount=jdbc.queryForObject("SELECT COUNT(*) FROM tournament_matches WHERE tournament_id=?",Integer.class,id);if(teamCount==null||teamCount<2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"At least 2 teams are required before activating the tournament");if(fixtureCount==null||fixtureCount<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Generate or add at least 1 fixture before activating the tournament");return;}if("ACTIVE".equals(current)&&"COMPLETED".equals(target)){Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM tournament_matches WHERE tournament_id=?",Integer.class,id);Integer completed=jdbc.queryForObject("SELECT COUNT(*) FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id WHERE tm.tournament_id=? AND m.status='COMPLETED'",Integer.class,id);if(total==null||total<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tournament has no fixtures to complete");if(!Objects.equals(total,completed))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"All tournament fixtures must be completed before completing the tournament");return;}throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tournament status transition: "+current+" -> "+target);}
 private void ensureTournamentEditable(UUID id){String status=jdbc.queryForObject("SELECT status FROM tournaments WHERE id=?",String.class,id);if(!"DRAFT".equals(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"Tournament can only be edited while in DRAFT status");}
 private UUID ownerId(Authentication a){try{return jdbc.queryForObject("SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",UUID.class,a.getName(),a.getName());}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"User not found");}}
 private void requireOwner(UUID id,Authentication a){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",Integer.class,id,ownerId(a));if(n==null||n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tournament not found");}

 private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
 private static String pairKey(UUID a,UUID b){return a.compareTo(b)<0?a+":"+b:b+":"+a;}
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
 private record MatchScore(UUID id,UUID iaTeam,int iaRuns,int iaBalls,UUID ibTeam,int ibRuns,int ibBalls){}
 private record Pair(UUID a,UUID b){}
}
