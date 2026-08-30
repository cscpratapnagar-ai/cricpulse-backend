package com.cricket.platform.tournament.service.impl;

import com.cricket.platform.tournament.TournamentController;
import com.cricket.platform.tournament.service.TournamentService;
import com.cricket.platform.tournament.service.TournamentStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class TournamentStatusServiceImpl implements TournamentStatusService {

    private final JdbcTemplate jdbc;
    private final TournamentService tournamentService;

    public TournamentStatusServiceImpl(JdbcTemplate jdbc, TournamentService tournamentService) {
        this.jdbc = jdbc;
        this.tournamentService = tournamentService;
    }

    @Override
    @Transactional
    public TournamentController.TournamentView changeStatus(
            UUID tournamentId,
            TournamentController.StatusRequest request,
            Authentication authentication
    ) {
        requireOwner(tournamentId, authentication);

        String currentStatus = jdbc.queryForObject(
                "SELECT status FROM tournaments WHERE id=?",
                String.class,
                tournamentId
        );

        String targetStatus = request.status().trim().toUpperCase(Locale.ROOT);

        validateTransition(currentStatus, targetStatus, tournamentId);

        jdbc.update(
                "UPDATE tournaments SET status=? WHERE id=?",
                targetStatus,
                tournamentId
        );

        return tournamentService.findById(tournamentId, authentication);
    }

    private void validateTransition(String current, String target, UUID tournamentId) {
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }

        if (current.equals(target)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tournament is already " + target
            );
        }

        if ("DRAFT".equals(current) && "ACTIVE".equals(target)) {
            Integer teamCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=?",
                    Integer.class,
                    tournamentId
            );

            Integer fixtureCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_matches WHERE tournament_id=?",
                    Integer.class,
                    tournamentId
            );

            if (teamCount == null || teamCount < 2) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "At least 2 teams are required before activating the tournament"
                );
            }

            if (fixtureCount == null || fixtureCount < 1) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Generate or add at least 1 fixture before activating the tournament"
                );
            }

            return;
        }

        if ("ACTIVE".equals(current) && "COMPLETED".equals(target)) {
            Integer totalFixtures = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_matches WHERE tournament_id=?",
                    Integer.class,
                    tournamentId
            );

            Integer completedFixtures = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_matches tm " +
                            "JOIN matches m ON m.id=tm.match_id " +
                            "WHERE tm.tournament_id=? AND m.status='COMPLETED'",
                    Integer.class,
                    tournamentId
            );

            if (totalFixtures == null || totalFixtures < 1) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Tournament has no fixtures to complete"
                );
            }

            if (!Objects.equals(totalFixtures, completedFixtures)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "All tournament fixtures must be completed before completing the tournament"
                );
            }

            return;
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid tournament status transition: " + current + " -> " + target
        );
    }

    private void requireOwner(UUID tournamentId, Authentication authentication) {
        UUID ownerId = ownerId(authentication);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class,
                tournamentId,
                ownerId
        );

        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }

    private UUID ownerId(Authentication authentication) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users " +
                            "WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",
                    UUID.class,
                    authentication.getName(),
                    authentication.getName()
            );
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
    }
}
