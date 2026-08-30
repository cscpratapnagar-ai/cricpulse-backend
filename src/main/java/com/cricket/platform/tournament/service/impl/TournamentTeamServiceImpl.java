package com.cricket.platform.tournament.service.impl;

import com.cricket.platform.tournament.TournamentController;
import com.cricket.platform.tournament.service.TournamentTeamService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class TournamentTeamServiceImpl implements TournamentTeamService {

    private final JdbcTemplate jdbc;

    public TournamentTeamServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TournamentController.TeamView> findTeams(
            UUID tournamentId,
            Authentication authentication
    ) {
        requireTournamentOwner(tournamentId, authentication);

        return jdbc.query(
                "SELECT t.id, t.name, t.city, tt.seed " +
                        "FROM tournament_teams tt " +
                        "JOIN teams t ON t.id = tt.team_id " +
                        "WHERE tt.tournament_id = ? " +
                        "ORDER BY COALESCE(tt.seed, 999), t.name",
                (row, rowNum) -> new TournamentController.TeamView(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("city"),
                        row.getObject("seed", Integer.class)
                ),
                tournamentId
        );
    }

    @Override
    @Transactional
    public TournamentController.TeamView addTeam(
            UUID tournamentId,
            UUID teamId,
            Authentication authentication
    ) {
        requireTournamentOwner(tournamentId, authentication);
        requireTeamOwner(teamId, authentication);
        ensureTournamentEditable(tournamentId);

        try {
            jdbc.update(
                    "INSERT INTO tournament_teams(tournament_id, team_id, seed) " +
                            "VALUES (?, ?, (" +
                            "SELECT COALESCE(MAX(seed), 0) + 1 " +
                            "FROM tournament_teams WHERE tournament_id = ?" +
                            "))",
                    tournamentId,
                    teamId,
                    tournamentId
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Team is already added to this tournament"
            );
        }

        return findTournamentTeam(tournamentId, teamId);
    }

    @Override
    @Transactional
    public void removeTeam(
            UUID tournamentId,
            UUID teamId,
            Authentication authentication
    ) {
        requireTournamentOwner(tournamentId, authentication);
        ensureTournamentEditable(tournamentId);

        jdbc.update(
                "DELETE FROM tournament_teams WHERE tournament_id=? AND team_id=?",
                tournamentId,
                teamId
        );
    }

    private TournamentController.TeamView findTournamentTeam(
            UUID tournamentId,
            UUID teamId
    ) {
        return jdbc.queryForObject(
                "SELECT t.id, t.name, t.city, tt.seed " +
                        "FROM tournament_teams tt " +
                        "JOIN teams t ON t.id = tt.team_id " +
                        "WHERE tt.tournament_id=? AND tt.team_id=?",
                (row, rowNum) -> new TournamentController.TeamView(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("city"),
                        row.getObject("seed", Integer.class)
                ),
                tournamentId,
                teamId
        );
    }

    private void requireTournamentOwner(
            UUID tournamentId,
            Authentication authentication
    ) {
        UUID ownerId = ownerId(authentication);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class,
                tournamentId,
                ownerId
        );

        if (count == null || count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Tournament not found"
            );
        }
    }

    private void requireTeamOwner(
            UUID teamId,
            Authentication authentication
    ) {
        UUID ownerId = ownerId(authentication);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams WHERE id=? AND owner_id=?",
                Integer.class,
                teamId,
                ownerId
        );

        if (count == null || count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You do not manage this team"
            );
        }
    }

    private void ensureTournamentEditable(UUID tournamentId) {
        String status = jdbc.queryForObject(
                "SELECT status FROM tournaments WHERE id=?",
                String.class,
                tournamentId
        );

        if (!"DRAFT".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tournament can only be edited while in DRAFT status"
            );
        }
    }

    private UUID ownerId(Authentication authentication) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users " +
                            "WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) " +
                            "OR CAST(id AS TEXT)=?",
                    UUID.class,
                    authentication.getName(),
                    authentication.getName()
            );
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "User not found"
            );
        }
    }
}
