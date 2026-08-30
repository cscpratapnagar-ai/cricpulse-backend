package com.cricket.platform.tournament.service.impl;

import com.cricket.platform.tournament.dto.request.ScheduleFixtureRequest;
import com.cricket.platform.tournament.dto.response.GenerateFixturesResponse;
import com.cricket.platform.tournament.dto.response.TournamentFixtureResponse;
import com.cricket.platform.tournament.dto.response.TournamentTeamResponse;
import com.cricket.platform.tournament.service.TournamentFixtureService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class TournamentFixtureServiceImpl implements TournamentFixtureService {

    private final JdbcTemplate jdbc;

    public TournamentFixtureServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TournamentFixtureResponse addMatch(UUID tournamentId, UUID matchId, String stage, Authentication authentication) {
        requireOwner(tournamentId, authentication);
        ensureEditable(tournamentId);

        Integer validTeams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matches m JOIN tournament_teams x ON x.tournament_id=? AND x.team_id=m.team_a_id JOIN tournament_teams y ON y.tournament_id=? AND y.team_id=m.team_b_id WHERE m.id=?",
                Integer.class, tournamentId, tournamentId, matchId);
        if (validTeams == null || validTeams == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both match teams must belong to the tournament");

        try {
            jdbc.update(
                    "INSERT INTO tournament_matches(tournament_id,match_id,stage,fixture_number) VALUES (?,?,?,(SELECT COALESCE(MAX(fixture_number),0)+1 FROM tournament_matches WHERE tournament_id=?))",
                    tournamentId, matchId, normalizeStage(stage), tournamentId);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Match is already linked to a tournament");
        }
        return findFixture(tournamentId, matchId);
    }

    @Override
    @Transactional
    public GenerateFixturesResponse generateFixtures(UUID tournamentId, Authentication authentication) {
        requireOwner(tournamentId, authentication);
        ensureEditable(tournamentId);

        TournamentConfig config = jdbc.queryForObject(
                "SELECT format,overs FROM tournaments WHERE id=?",
                (row, index) -> new TournamentConfig(row.getString("format"), row.getInt("overs")),
                tournamentId);

        List<TournamentTeamResponse> teams = jdbc.query(
                "SELECT t.id,t.name,t.city,tt.seed FROM tournament_teams tt JOIN teams t ON t.id=tt.team_id WHERE tt.tournament_id=? ORDER BY COALESCE(tt.seed,999999),t.name",
                (row, index) -> new TournamentTeamResponse(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("city"), row.getObject("seed", Integer.class)),
                tournamentId);

        if (teams.size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least 2 teams before generating fixtures");

        String format = config.format().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("T10", "T20", "ODI", "CUSTOM").contains(format))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tournament format is not supported for automatic fixture generation");

        Set<String> existingPairs = new HashSet<>();
        jdbc.query(
                "SELECT m.team_a_id,m.team_b_id FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id WHERE tm.tournament_id=?",
                row -> existingPairs.add(pairKey(row.getObject("team_a_id", UUID.class), row.getObject("team_b_id", UUID.class))),
                tournamentId);

        Integer maxFixture = jdbc.queryForObject(
                "SELECT MAX(fixture_number) FROM tournament_matches WHERE tournament_id=?", Integer.class, tournamentId);
        int nextFixture = (maxFixture == null ? 0 : maxFixture) + 1;
        int generated = 0;
        int skipped = 0;

        for (int i = 0; i < teams.size(); i++) {
            for (int j = i + 1; j < teams.size(); j++) {
                TournamentTeamResponse home = teams.get(i);
                TournamentTeamResponse away = teams.get(j);
                String key = pairKey(home.id(), away.id());
                if (existingPairs.contains(key)) {
                    skipped++;
                    continue;
                }

                UUID matchId = UUID.randomUUID();
                String name = "Fixture " + nextFixture + ": " + home.name() + " vs " + away.name();
                jdbc.update(
                        "INSERT INTO matches(id,name,team_a_id,team_b_id,format,total_overs,scheduled_at,status) VALUES (?,?,?,?,?,?,?, 'SCHEDULED')",
                        matchId, name, home.id(), away.id(), format, config.overs(), null);
                jdbc.update(
                        "INSERT INTO tournament_matches(tournament_id,match_id,stage,fixture_number) VALUES (?,?, 'LEAGUE', ?)",
                        tournamentId, matchId, nextFixture);

                existingPairs.add(key);
                nextFixture++;
                generated++;
            }
        }

        return new GenerateFixturesResponse(tournamentId, generated, skipped, generated + skipped,
                findFixtures(tournamentId, authentication));
    }

    @Override
    public List<TournamentFixtureResponse> findFixtures(UUID tournamentId, Authentication authentication) {
        requireOwner(tournamentId, authentication);
        return jdbc.query(
                "SELECT tm.match_id,tm.fixture_number,tm.stage,m.name,m.team_a_id,x.name team_a_name,m.team_b_id,y.name team_b_name,m.status,m.scheduled_at FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id JOIN teams x ON x.id=m.team_a_id JOIN teams y ON y.id=m.team_b_id WHERE tm.tournament_id=? ORDER BY COALESCE(tm.fixture_number,999999),m.scheduled_at NULLS LAST",
                (row, index) -> fixtureResponse(row), tournamentId);
    }

    @Override
    public TournamentFixtureResponse scheduleFixture(UUID tournamentId, UUID matchId,
                                                      ScheduleFixtureRequest request, Authentication authentication) {
        requireOwner(tournamentId, authentication);

        if (request.scheduledAt().isBefore(OffsetDateTime.now().minusMinutes(1)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fixture time must be in the future");

        Integer linked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournament_matches WHERE tournament_id=? AND match_id=?",
                Integer.class, tournamentId, matchId);
        if (linked == null || linked == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fixture is not linked to this tournament");

        Integer conflict = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matches m JOIN tournament_matches tm ON tm.match_id=m.id WHERE tm.tournament_id=? AND m.scheduled_at=? AND m.status IN ('SCHEDULED','LIVE') AND m.id<>?",
                Integer.class, tournamentId, request.scheduledAt(), matchId);
        if (conflict != null && conflict > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another tournament fixture is already scheduled at this exact time");

        jdbc.update(
                "UPDATE matches SET scheduled_at=?, status=CASE WHEN status='COMPLETED' THEN status ELSE 'SCHEDULED' END WHERE id=?",
                request.scheduledAt(), matchId);

        return findFixture(tournamentId, matchId);
    }

    private TournamentFixtureResponse findFixture(UUID tournamentId, UUID matchId) {
        return jdbc.queryForObject(
                "SELECT tm.match_id,tm.fixture_number,tm.stage,m.name,m.team_a_id,x.name team_a_name,m.team_b_id,y.name team_b_name,m.status,m.scheduled_at FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id JOIN teams x ON x.id=m.team_a_id JOIN teams y ON y.id=m.team_b_id WHERE tm.tournament_id=? AND tm.match_id=?",
                (row, index) -> fixtureResponse(row), tournamentId, matchId);
    }

    private TournamentFixtureResponse fixtureResponse(java.sql.ResultSet row) throws java.sql.SQLException {
        Object scheduledAt = row.getObject("scheduled_at");
        return new TournamentFixtureResponse(
                row.getObject("match_id", UUID.class), row.getObject("fixture_number", Integer.class),
                row.getString("stage"), row.getString("name"), row.getObject("team_a_id", UUID.class),
                row.getString("team_a_name"), row.getObject("team_b_id", UUID.class),
                row.getString("team_b_name"), row.getString("status"),
                scheduledAt == null ? null : scheduledAt.toString());
    }

    private void ensureEditable(UUID tournamentId) {
        String status = jdbc.queryForObject("SELECT status FROM tournaments WHERE id=?", String.class, tournamentId);
        if (!"DRAFT".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tournament can only be edited while in DRAFT status");
    }

    private void requireOwner(UUID tournamentId, Authentication authentication) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class, tournamentId, ownerId(authentication));
        if (count == null || count == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
    }

    private UUID ownerId(Authentication authentication) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",
                    UUID.class, authentication.getName(), authentication.getName());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
    }

    private String normalizeStage(String stage) {
        return stage == null || stage.isBlank() ? "LEAGUE" : stage.trim().toUpperCase(Locale.ROOT);
    }

    private String pairKey(UUID first, UUID second) {
        return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first;
    }

    private record TournamentConfig(String format, int overs) {}
}
