package com.cricket.platform.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class BulkCreateTeamPlayers {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public BulkCreateTeamPlayers(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Result execute(UUID teamId, Request request, Authentication authentication) {
        requireManager(teamId, authentication);
        if (request.players() == null || request.players().isEmpty()) {
            throw new TeamController.TeamMembershipException("PLAYERS_REQUIRED", "Add at least one player.");
        }
        if (request.players().size() > 50) {
            throw new TeamController.TeamMembershipException("TOO_MANY_PLAYERS", "You can create at most 50 players at once.");
        }

        List<CreatedPlayer> created = new ArrayList<>();
        List<SkippedPlayer> skipped = new ArrayList<>();

        for (Input player : request.players()) {
            String name = player.fullName() == null ? "" : player.fullName().trim();
            String email = player.email() == null ? "" : player.email().trim().toLowerCase(Locale.ROOT);
            String phone = player.phone() == null ? "" : player.phone().trim();
            String password = player.password() == null ? "" : player.password();

            if (name.length() < 2 || email.isBlank() || phone.isBlank()) {
                skipped.add(new SkippedPlayer(name, email, "Name, email and mobile are required."));
                continue;
            }
            if (password.length() < 8) {
                skipped.add(new SkippedPlayer(name, email, "Password must contain at least 8 characters."));
                continue;
            }

            UUID existingUserId = jdbc.query(
                    "SELECT id FROM users WHERE lower(email) = lower(?) OR phone = ? LIMIT 1",
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null, email, phone);
            if (existingUserId != null) {
                UUID existingPlayerId = jdbc.query(
                        "SELECT id FROM players WHERE user_id = ?",
                        rs -> rs.next() ? rs.getObject("id", UUID.class) : null, existingUserId);
                if (existingPlayerId != null) {
                    Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id = ? AND player_id = ?", Integer.class, teamId, existingPlayerId);
                    if (member != null && member > 0) {
                        skipped.add(new SkippedPlayer(name, email, "This player is already in the team."));
                    } else {
                        jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, 'PLAYER')", teamId, existingPlayerId);
                        created.add(new CreatedPlayer(existingPlayerId, existingUserId, name, email, phone, null, false));
                    }
                } else {
                    skipped.add(new SkippedPlayer(name, email, "An account already exists for this email or mobile, but it has no player profile."));
                }
                continue;
            }

            UUID userId = UUID.randomUUID();
            UUID playerId = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id, full_name, email, phone, role, password_hash) VALUES (?, ?, ?, ?, 'PLAYER', ?)",
                    userId, name, email, phone, passwordEncoder.encode(password));
            jdbc.update("INSERT INTO players(id, user_id) VALUES (?, ?)", playerId, userId);
            jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, 'PLAYER')", teamId, playerId);
            created.add(new CreatedPlayer(playerId, userId, name, email, phone, password, true));
        }

        return new Result(created, skipped, created.size(), skipped.size());
    }

    private void requireManager(UUID teamId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new TeamController.TeamMembershipException("AUTHENTICATION_REQUIRED", "Authentication is required.");
        }
        Integer owner = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id WHERE t.id = ? AND (lower(trim(u.email)) = lower(trim(?)) OR CAST(u.id AS TEXT) = ?)",
                Integer.class, teamId, authentication.getName(), authentication.getName());
        if (owner != null && owner > 0) return;
        String role = jdbc.query(
                "SELECT tm.role FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? AND (lower(trim(u.email)) = lower(trim(?)) OR CAST(u.id AS TEXT) = ?)",
                rs -> rs.next() ? rs.getString(1) : null, teamId, authentication.getName(), authentication.getName());
        if (!"MANAGER".equals(role) && !"CAPTAIN".equals(role)) {
            throw new TeamController.TeamMembershipException("TEAM_MANAGE_ACCESS_DENIED", "Only the team owner, manager or captain can create squad players.");
        }
    }

    public record Request(@Valid List<Input> players) {}

    public record Input(
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotBlank @Email String email,
            @NotBlank String phone,
            @NotBlank String password) {}

    public record CreatedPlayer(UUID playerId, UUID userId, String fullName, String email, String phone,
                                String temporaryPassword, boolean accountCreated) {}

    public record SkippedPlayer(String fullName, String email, String reason) {}

    public record Result(List<CreatedPlayer> created, List<SkippedPlayer> skipped, int createdCount, int skippedCount) {}
}
