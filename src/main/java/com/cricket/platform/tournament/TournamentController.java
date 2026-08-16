package com.cricket.platform.tournament;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {
    private final JdbcTemplate jdbc;
    public TournamentController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/mine")
    List<TournamentView> mine(Authentication auth) {
        UUID owner = ownerId(auth);
        return jdbc.query("SELECT id,name,format,overs,location,start_date,status,created_at FROM tournaments WHERE owner_id=? ORDER BY created_at DESC",
                (rs,n)->new TournamentView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("format"),rs.getInt("overs"),rs.getString("location"),rs.getObject("start_date",LocalDate.class),rs.getString("status"),rs.getObject("created_at").toString()), owner);
    }

    @PostMapping
    TournamentView create(@Valid @RequestBody CreateRequest request, Authentication auth) {
        UUID owner = ownerId(auth); UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO tournaments(id,name,format,overs,location,start_date,status,owner_id) VALUES (?,?,?,?,?,?,?,?)",
                id,request.name().trim(),request.format().trim().toUpperCase(Locale.ROOT),request.overs(),blankToNull(request.location()),request.startDate(),"DRAFT",owner);
        return get(id,auth);
    }

    @GetMapping("/{id}")
    TournamentView get(@PathVariable UUID id, Authentication auth) {
        requireOwner(id,auth);
        return jdbc.queryForObject("SELECT id,name,format,overs,location,start_date,status,created_at FROM tournaments WHERE id=?",
                (rs,n)->new TournamentView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("format"),rs.getInt("overs"),rs.getString("location"),rs.getObject("start_date",LocalDate.class),rs.getString("status"),rs.getObject("created_at").toString()),id);
    }

    @GetMapping("/{id}/teams")
    List<TeamView> teams(@PathVariable UUID id, Authentication auth) {
        requireOwner(id,auth);
        return jdbc.query("SELECT t.id,t.name,t.city,tt.seed FROM tournament_teams tt JOIN teams t ON t.id=tt.team_id WHERE tt.tournament_id=? ORDER BY COALESCE(tt.seed,999),t.name",
                (rs,n)->new TeamView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("city"),rs.getObject("seed",Integer.class)),id);
    }

    @PostMapping("/{id}/teams/{teamId}")
    TeamView addTeam(@PathVariable UUID id,@PathVariable UUID teamId,Authentication auth){
        requireOwner(id,auth); requireTeamOwner(teamId,auth);
        try { jdbc.update("INSERT INTO tournament_teams(tournament_id,team_id,seed) VALUES (?,?,(SELECT COALESCE(MAX(seed),0)+1 FROM tournament_teams WHERE tournament_id=?))",id,teamId,id); }
        catch(DataIntegrityViolationException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Team is already added to this tournament"); }
        return jdbc.queryForObject("SELECT t.id,t.name,t.city,tt.seed FROM tournament_teams tt JOIN teams t ON t.id=tt.team_id WHERE tt.tournament_id=? AND tt.team_id=?",
                (rs,n)->new TeamView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("city"),rs.getObject("seed",Integer.class)),id,teamId);
    }

    @DeleteMapping("/{id}/teams/{teamId}")
    void removeTeam(@PathVariable UUID id,@PathVariable UUID teamId,Authentication auth){ requireOwner(id,auth); jdbc.update("DELETE FROM tournament_teams WHERE tournament_id=? AND team_id=?",id,teamId); }

    @PostMapping("/{id}/matches/{matchId}")
    FixtureView addMatch(@PathVariable UUID id,@PathVariable UUID matchId,@RequestParam(defaultValue="LEAGUE") String stage,Authentication auth){
        requireOwner(id,auth);
        Integer ok=jdbc.queryForObject("SELECT COUNT(*) FROM matches m JOIN tournament_teams a ON a.tournament_id=? AND a.team_id=m.team_a_id JOIN tournament_teams b ON b.tournament_id=? AND b.team_id=m.team_b_id WHERE m.id=?",Integer.class,id,id,matchId);
        if(ok==null||ok==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Both match teams must belong to the tournament");
        try { jdbc.update("INSERT INTO tournament_matches(tournament_id,match_id,stage,fixture_number) VALUES (?,?,?,(SELECT COALESCE(MAX(fixture_number),0)+1 FROM tournament_matches WHERE tournament_id=?))",id,matchId,stage.toUpperCase(Locale.ROOT),id); }
        catch(DataIntegrityViolationException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Match is already linked to a tournament"); }
        return fixture(id,matchId);
    }

    @GetMapping("/{id}/fixtures")
    List<FixtureView> fixtures(@PathVariable UUID id,Authentication auth){ requireOwner(id,auth); return jdbc.query("SELECT tm.match_id,tm.fixture_number,tm.stage,m.name,m.team_a_id,a.name team_a_name,m.team_b_id,b.name team_b_name,m.status,m.scheduled_at FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id JOIN teams a ON a.id=m.team_a_id JOIN teams b ON b.id=m.team_b_id WHERE tm.tournament_id=? ORDER BY COALESCE(tm.fixture_number,999999),m.scheduled_at NULLS LAST",(rs,n)->new FixtureView(rs.getObject("match_id",UUID.class),rs.getObject("fixture_number",Integer.class),rs.getString("stage"),rs.getString("name"),rs.getObject("team_a_id",UUID.class),rs.getString("team_a_name"),rs.getObject("team_b_id",UUID.class),rs.getString("team_b_name"),rs.getString("status"),rs.getObject("scheduled_at") == null ? null : rs.getObject("scheduled_at").toString()),id); }

    @GetMapping("/{id}/points-table")
    List<PointRow> points(@PathVariable UUID id,Authentication auth){
        requireOwner(id,auth);
        List<PointRow> rows=new ArrayList<>();
        List<TeamView> teams=teams(id,auth);
        for(TeamView team:teams){ int played=0,wins=0,losses=0,ties=0,runsFor=0,runsAgainst=0; double ballsFor=0,ballsAgainst=0;
            List<MatchScore> ms=jdbc.query("SELECT m.id,m.team_a_id,m.team_b_id,ia.total_runs a_runs,ia.legal_balls a_balls,ib.total_runs b_runs,ib.legal_balls b_balls FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id LEFT JOIN innings ia ON ia.match_id=m.id AND ia.innings_number=1 LEFT JOIN innings ib ON ib.match_id=m.id AND ib.innings_number=2 WHERE tm.tournament_id=? AND m.status='COMPLETED' AND (m.team_a_id=? OR m.team_b_id=?) ORDER BY m.created_at",(rs,n)->new MatchScore(rs.getObject("id",UUID.class),rs.getObject("team_a_id",UUID.class),rs.getObject("team_b_id",UUID.class),rs.getInt("a_runs"),rs.getInt("a_balls"),rs.getInt("b_runs"),rs.getInt("b_balls")),id,team.id(),team.id());
            for(MatchScore m:ms){played++;boolean a=team.id().equals(m.a());int own=a?m.ar():m.br(),opp=a?m.br():m.ar();int ownBalls=a?m.ab():m.bb(),oppBalls=a?m.bb():m.ab();runsFor+=own;runsAgainst+=opp;ballsFor+=ownBalls;ballsAgainst+=oppBalls;if(own>opp)wins++;else if(own<opp)losses++;else ties++;}
            double nrr=ballsFor>0&&ballsAgainst>0?(runsFor/(ballsFor/6.0))-(runsAgainst/(ballsAgainst/6.0)):0;int pts=wins*2+ties;
            rows.add(new PointRow(team.id(),team.name(),played,wins,losses,ties,pts,runsFor,runsAgainst,Math.round(nrr*1000.0)/1000.0));
        }
        rows.sort(Comparator.comparingInt(PointRow::points).reversed().thenComparingDouble(PointRow::nrr).reversed().thenComparingInt(PointRow::runsFor).reversed());
        return rows;
    }

    private FixtureView fixture(UUID tid,UUID mid){return jdbc.queryForObject("SELECT tm.match_id,tm.fixture_number,tm.stage,m.name,m.team_a_id,a.name team_a_name,m.team_b_id,b.name team_b_name,m.status,m.scheduled_at FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id JOIN teams a ON a.id=m.team_a_id JOIN teams b ON b.id=m.team_b_id WHERE tm.tournament_id=? AND tm.match_id=?",(rs,n)->new FixtureView(rs.getObject("match_id",UUID.class),rs.getObject("fixture_number",Integer.class),rs.getString("stage"),rs.getString("name"),rs.getObject("team_a_id",UUID.class),rs.getString("team_a_name"),rs.getObject("team_b_id",UUID.class),rs.getString("team_b_name"),rs.getString("status"),rs.getObject("scheduled_at")==null?null:rs.getObject("scheduled_at").toString()),tid,mid);}
    private UUID ownerId(Authentication auth){UUID id=jdbc.queryForObject("SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",UUID.class,auth.getName(),auth.getName());if(id==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return id;}
    private void requireOwner(UUID id,Authentication auth){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",Integer.class,id,ownerId(auth));if(n==null||n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tournament not found");}
    private void requireTeamOwner(UUID id,Authentication auth){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id=? AND owner_id=?",Integer.class,id,ownerId(auth));if(n==null||n==0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You do not manage this team");}
    private static String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
    public record CreateRequest(@NotBlank String name,@NotBlank String format,@Positive int overs,String location,LocalDate startDate){}
    public record TournamentView(UUID id,String name,String format,int overs,String location,LocalDate startDate,String status,String createdAt){}
    public record TeamView(UUID id,String name,String city,Integer seed){}
    public record FixtureView(UUID matchId,Integer fixtureNumber,String stage,String matchName,UUID teamAId,String teamAName,UUID teamBId,String teamBName,String status,String scheduledAt){}
    public record PointRow(UUID teamId,String teamName,int played,int wins,int losses,int ties,int points,int runsFor,int runsAgainst,double nrr){}
    private record MatchScore(UUID id,UUID a,UUID b,int ar,int ab,int br,int bb){}
}
