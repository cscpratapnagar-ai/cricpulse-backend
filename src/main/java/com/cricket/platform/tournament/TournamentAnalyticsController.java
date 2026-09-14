package com.cricket.platform.tournament;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentAnalyticsController {
    private final JdbcTemplate jdbc;
    private final GetTournamentAnalytics analytics;

    public TournamentAnalyticsController(JdbcTemplate jdbc, GetTournamentAnalytics analytics) {
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    @GetMapping("/{id}/analytics")
    public GetTournamentAnalytics.TournamentAnalytics get(@PathVariable UUID id, Authentication authentication) {
        requireOwner(id, authentication);
        return analytics.get(id);
    }

    private void requireOwner(UUID tournamentId, Authentication authentication) {
        UUID ownerId;
        try {
            ownerId = jdbc.queryForObject(
                    "SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",
                    UUID.class, authentication.getName(), authentication.getName());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class, tournamentId, ownerId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }
}
