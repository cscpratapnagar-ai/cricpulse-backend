package com.cricket.platform.tournament;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/staff")
public class TournamentStaffController {
    private final JdbcTemplate jdbc;

    public TournamentStaffController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<ManagerView> list(@PathVariable UUID tournamentId, Authentication authentication) {
        requireTournamentAccess(tournamentId, authentication);
        return jdbc.query("""
                SELECT ts.tournament_id, ts.user_id, u.full_name, u.email, ts.role, ts.assigned_at
                FROM tournament_staff ts
                JOIN users u ON u.id = ts.user_id
                WHERE ts.tournament_id = ?
                ORDER BY u.full_name, u.email
                """,
                (rs, row) -> new ManagerView(
                        rs.getObject("tournament_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getObject("assigned_at", OffsetDateTime.class)), tournamentId);
    }

    @GetMapping("/candidates")
    List<ManagerCandidate> candidates(@PathVariable UUID tournamentId,
                                      @RequestParam("q") String query,
                                      Authentication authentication) {
        requireTournamentOwner(tournamentId, authentication);
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (term.length() < 2) return List.of();
        String pattern = "%" + term + "%";
        return jdbc.query("""
                SELECT id, full_name, email
                FROM users
                WHERE LOWER(COALESCE(full_name, '')) LIKE ?
                   OR LOWER(COALESCE(email, '')) LIKE ?
                ORDER BY full_name, email
                LIMIT 20
                """,
                (rs, row) -> new ManagerCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email")), pattern, pattern);
    }

    @PutMapping("/manager")
    void assignManager(@PathVariable UUID tournamentId,
                       @Valid @RequestBody AssignManagerRequest request,
                       Authentication authentication) {
        UUID ownerId = requireTournamentOwner(tournamentId, authentication);
        if (ownerId.equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The tournament owner already has full access.");
        }
        Integer userExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, request.userId());
        if (userExists == null || userExists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected user account was not found.");
        }
        jdbc.update("""
                INSERT INTO tournament_staff(tournament_id, user_id, role)
                VALUES (?, ?, 'MANAGER')
                ON CONFLICT (tournament_id, user_id, role)
                DO UPDATE SET assigned_at = now()
                """, tournamentId, request.userId());
    }

    @DeleteMapping("/manager/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeManager(@PathVariable UUID tournamentId, @PathVariable UUID userId,
                       Authentication authentication) {
        requireTournamentOwner(tournamentId, authentication);
        jdbc.update("""
                DELETE FROM tournament_staff
                WHERE tournament_id = ? AND user_id = ? AND role = 'MANAGER'
                """, tournamentId, userId);
    }

    void requireTournamentAccess(UUID tournamentId, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tournaments t
                WHERE t.id = ?
                  AND (t.owner_id = ? OR EXISTS (
                    SELECT 1 FROM tournament_staff ts
                    WHERE ts.tournament_id = t.id AND ts.user_id = ? AND ts.role = 'MANAGER'
                  ))
                """, Integer.class, tournamentId, userId, userId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found.");
        }
    }

    UUID requireTournamentOwner(UUID tournamentId, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournaments WHERE id = ? AND owner_id = ?",
                Integer.class, tournamentId, userId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the tournament owner can assign or remove tournament managers.");
        }
        return userId;
    }

    private UUID currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users WHERE LOWER(TRIM(email)) = LOWER(TRIM(?)) OR CAST(id AS TEXT) = ?",
                    UUID.class, authentication.getName(), authentication.getName());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found.");
        }
    }

    public record AssignManagerRequest(@NotNull UUID userId) {}
    public record ManagerCandidate(UUID userId, String fullName, String email) {}
    public record ManagerView(UUID tournamentId, UUID userId, String fullName, String email,
                              String role, OffsetDateTime assignedAt) {}
}
