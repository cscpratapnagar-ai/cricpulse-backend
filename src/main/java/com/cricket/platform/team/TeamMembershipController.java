package com.cricket.platform.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/teams/{teamId}/members")
public class TeamMembershipController {
    private static final String ROLES = "PLAYER,MANAGER,CAPTAIN,VICE_CAPTAIN";

    private final JdbcTemplate jdbc;

    public TeamMembershipController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<MemberView> list(@PathVariable UUID teamId, Authentication authentication) {
        requireTeamAccess(teamId, authentication.getName());
        return jdbc.query(
                "SELECT tm.team_id, tm.player_id, p.user_id, u.full_name, u.email, u.phone, tm.role " +
                "FROM team_members tm JOIN players p ON p.id = tm.player_id " +
                "JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? " +
                "ORDER BY CASE tm.role WHEN 'OWNER' THEN 1 WHEN 'MANAGER' THEN 2 WHEN 'CAPTAIN' THEN 3 WHEN 'VICE_CAPTAIN' THEN 4 ELSE 5 END, u.full_name",
                (rs, row) -> new MemberView(
                        rs.getObject("team_id", UUID.class),
                        rs.getObject("player_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role")
                ), teamId);
    }

    @PostMapping
    MemberView add(@PathVariable UUID teamId, @Valid @RequestBody AddMemberRequest request, Authentication authentication) {
        requireOwner(teamId, authentication.getName());
        String role = normalizeRole(request.role());

        UUID playerId = jdbc.query(
                "SELECT p.id FROM players p JOIN users u ON u.id = p.user_id WHERE lower(u.email) = lower(?)",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                request.email().trim());
        if (playerId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND: No player profile exists for this email");
        }

        try {
            jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, ?) ON CONFLICT (team_id, player_id) DO UPDATE SET role = EXCLUDED.role", teamId, playerId, role);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TEAM_ROLE_ALREADY_ASSIGNED: This role is already assigned in this team");
        }
        return findMember(teamId, playerId);
    }

    @PatchMapping("/{playerId}")
    MemberView changeRole(@PathVariable UUID teamId, @PathVariable UUID playerId, @Valid @RequestBody RoleRequest request, Authentication authentication) {
        requireOwner(teamId, authentication.getName());
        String role = normalizeRole(request.role());
        if (!exists(teamId, playerId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TEAM_MEMBER_NOT_FOUND");
        try {
            jdbc.update("UPDATE team_members SET role = ? WHERE team_id = ? AND player_id = ?", role, teamId, playerId);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TEAM_ROLE_ALREADY_ASSIGNED: This role is already assigned in this team");
        }
        return findMember(teamId, playerId);
    }

    @DeleteMapping("/{playerId}")
    void remove(@PathVariable UUID teamId, @PathVariable UUID playerId, Authentication authentication) {
        requireOwner(teamId, authentication.getName());
        if (!exists(teamId, playerId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TEAM_MEMBER_NOT_FOUND");
        if (isOwner(teamId, playerId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "OWNER_CANNOT_BE_REMOVED");
        jdbc.update("DELETE FROM team_members WHERE team_id = ? AND player_id = ?", teamId, playerId);
    }

    private String normalizeRole(String value) {
        String role = value == null ? "" : value.trim().toUpperCase();
        if (!ROLES.contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TEAM_ROLE");
        return role;
    }

    private void requireTeamAccess(UUID teamId, String email) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM teams t WHERE t.id = ? AND lower((SELECT u.email FROM users u WHERE u.id = t.owner_id)) = lower(?)", Integer.class, teamId, email);
        Integer member = jdbc.queryForObject("SELECT count(*) FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? AND lower(u.email) = lower(?)", Integer.class, teamId, email);
        if ((count == null || count == 0) && (member == null || member == 0)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TEAM_ACCESS_DENIED");
    }

    private void requireOwner(UUID teamId, String email) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM teams t JOIN users u ON u.id = t.owner_id WHERE t.id = ? AND lower(u.email) = lower(?)", Integer.class, teamId, email);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED");
    }

    private boolean exists(UUID teamId, UUID playerId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ? AND player_id = ?", Integer.class, teamId, playerId);
        return count != null && count > 0;
    }

    private boolean isOwner(UUID teamId, UUID playerId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ? AND player_id = ? AND role = 'OWNER'", Integer.class, teamId, playerId);
        return count != null && count > 0;
    }

    private MemberView findMember(UUID teamId, UUID playerId) {
        return jdbc.queryForObject(
                "SELECT tm.team_id, tm.player_id, p.user_id, u.full_name, u.email, u.phone, tm.role FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? AND tm.player_id = ?",
                (rs, row) -> new MemberView(rs.getObject("team_id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"), rs.getString("role")), teamId, playerId);
    }

    public record AddMemberRequest(@NotBlank String email, @NotBlank String role) {}
    public record RoleRequest(@NotBlank String role) {}
    public record MemberView(UUID teamId, UUID playerId, UUID userId, String fullName, String email, String phone, String role) {}
}
