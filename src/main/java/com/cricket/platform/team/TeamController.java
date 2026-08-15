package com.cricket.platform.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/teams")
public class TeamController {
    private final CreateTeam createTeam;
    private final GetTeam getTeam;
    private final JdbcTemplate jdbc;

    public TeamController(CreateTeam createTeam, GetTeam getTeam, JdbcTemplate jdbc) {
        this.createTeam = createTeam;
        this.getTeam = getTeam;
        this.jdbc = jdbc;
    }

    @PostMapping
    CreateTeam.TeamResponse create(@Valid @RequestBody CreateTeam.Request request, Authentication authentication) {
        return createTeam.execute(request, authentication.getName());
    }

    @GetMapping("/mine")
    List<GetTeam.TeamView> mine(Authentication authentication) {
        return jdbc.query(
                "SELECT t.id, t.name, t.city, t.owner_id FROM teams t JOIN users u ON u.id = t.owner_id " +
                "WHERE LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ? ORDER BY t.name",
                (rs, row) -> new GetTeam.TeamView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("city"),
                        rs.getObject("owner_id", UUID.class)
                ), authentication.getName(), authentication.getName()
        );
    }

    @GetMapping("/{id}/members")
    List<MemberView> members(@PathVariable UUID id, Authentication authentication) {
        requireTeamMemberOrOwner(id, authentication);
        Integer memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id = ?", Integer.class, id);
        if (memberCount == null || memberCount == 0) return List.of();
        return jdbc.query(
                "SELECT tm.team_id, tm.player_id, p.user_id, u.full_name, u.email, u.phone, tm.role " +
                "FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id " +
                "WHERE tm.team_id = ? ORDER BY u.full_name",
                (rs, row) -> new MemberView(rs.getObject("team_id", UUID.class), rs.getObject("player_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("phone"), rs.getString("role")), id);
    }

    @GetMapping("/{id}/access")
    TeamAccess access(@PathVariable UUID id, Authentication authentication) {
        String role = currentTeamRole(id, authentication);
        return new TeamAccess(id, role, canManage(role));
    }

    /** Search existing registered player profiles. A player must have both a user account and a player profile. */
    @GetMapping("/{id}/player-search")
    List<PlayerSearchView> searchPlayers(@PathVariable UUID id, @RequestParam("q") @NotBlank String query,
                                         Authentication authentication) {
        requireTeamManager(id, authentication);
        String q = query.trim();
        if (q.length() < 2) return List.of();
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(
                "SELECT p.id AS player_id, p.user_id, u.full_name, u.email, u.phone " +
                "FROM players p JOIN users u ON u.id = p.user_id " +
                "WHERE LOWER(COALESCE(u.full_name, '')) LIKE ? " +
                "OR LOWER(COALESCE(u.email, '')) LIKE ? " +
                "OR LOWER(COALESCE(u.phone, '')) LIKE ? " +
                "ORDER BY u.full_name LIMIT 20",
                (rs, row) -> new PlayerSearchView(rs.getObject("player_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"), rs.getString("email"), rs.getString("phone")),
                like, like, like);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    MemberView addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberRequest request, Authentication authentication) {
        requireTeamManager(id, authentication);
        String role = normalizeRole(request.role());
        if ("OWNER".equals(role)) {
            throw new TeamMembershipException("OWNER_ROLE_NOT_ASSIGNABLE", "The team owner is assigned automatically.");
        }

        UUID playerId = resolvePlayerId(request);
        Integer alreadyMember = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ? AND player_id = ?", Integer.class, id, playerId);
        if (alreadyMember != null && alreadyMember > 0) {
            throw new TeamMembershipException("PLAYER_ALREADY_IN_TEAM", "This player is already a member of this team.");
        }

        try {
            jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, ?)", id, playerId, role);
        } catch (DuplicateKeyException ex) {
            throw new TeamMembershipException("PLAYER_ALREADY_IN_TEAM", "This player is already a member of this team.");
        } catch (DataIntegrityViolationException ex) {
            throw new TeamMembershipException("TEAM_ROLE_ALREADY_ASSIGNED", "That team role is already assigned to another member.");
        }
        return member(id, playerId);
    }

    @PatchMapping("/{id}/members/{playerId}")
    MemberView changeRole(@PathVariable UUID id, @PathVariable UUID playerId, @Valid @RequestBody ChangeRoleRequest request, Authentication authentication) {
        requireTeamManager(id, authentication);
        String role = normalizeRole(request.role());
        if ("OWNER".equals(role)) throw new TeamMembershipException("OWNER_ROLE_NOT_ASSIGNABLE", "The team owner cannot be reassigned through squad role management.");
        Integer memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id = ? AND player_id = ?", Integer.class, id, playerId);
        if (memberCount == null || memberCount == 0) throw new TeamMembershipException("TEAM_MEMBER_NOT_FOUND", "This player is not a member of the team.");
        try {
            jdbc.update("UPDATE team_members SET role = ? WHERE team_id = ? AND player_id = ?", role, id, playerId);
        } catch (DataIntegrityViolationException ex) {
            throw new TeamMembershipException("TEAM_ROLE_ALREADY_ASSIGNED", "That team role is already assigned to another member.");
        }
        return member(id, playerId);
    }

    @DeleteMapping("/{id}/members/{playerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable UUID id, @PathVariable UUID playerId, Authentication authentication) {
        requireTeamManager(id, authentication);
        String role = jdbc.query("SELECT role FROM team_members WHERE team_id = ? AND player_id = ?", rs -> rs.next() ? rs.getString(1) : null, id, playerId);
        if (role == null) throw new TeamMembershipException("TEAM_MEMBER_NOT_FOUND", "This player is not a member of the team.");
        if ("OWNER".equals(role)) throw new TeamMembershipException("OWNER_CANNOT_BE_REMOVED", "The team owner cannot be removed from the team.");
        jdbc.update("DELETE FROM team_members WHERE team_id = ? AND player_id = ?", id, playerId);
    }

    @GetMapping("/{id}")
    GetTeam.TeamView get(@PathVariable UUID id) { return getTeam.execute(id); }

    @GetMapping
    List<GetTeam.TeamView> list() {
        return jdbc.query("SELECT id, name, city, owner_id FROM teams ORDER BY name",
                (rs, row) -> new GetTeam.TeamView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("city"), rs.getObject("owner_id", UUID.class)));
    }

    private UUID resolvePlayerId(AddMemberRequest request) {
        if (request.playerId() != null) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE id = ?", Integer.class, request.playerId());
            if (exists != null && exists > 0) return request.playerId();
            throw new TeamMembershipException("PLAYER_NOT_FOUND", "No registered player profile was found for this player ID.");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new TeamMembershipException("PLAYER_REQUIRED", "Select an existing player profile before adding the player.");
        }
        UUID playerId = jdbc.query(
                "SELECT p.id FROM players p JOIN users u ON u.id = p.user_id " +
                "WHERE LOWER(TRIM(u.email)) = LOWER(TRIM(?))",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, request.email().trim());
        if (playerId == null) {
            throw new TeamMembershipException("PLAYER_NOT_FOUND", "No registered player profile was found for this email. Search and select an existing player profile.");
        }
        return playerId;
    }

    private void requireTeamMemberOrOwner(UUID teamId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        Integer owner = jdbc.queryForObject("SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id WHERE t.id = ? AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)", Integer.class, teamId, authentication.getName(), authentication.getName());
        if (owner != null && owner > 0) return;
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)", Integer.class, teamId, authentication.getName(), authentication.getName());
        if (member == null || member == 0) throw new TeamMembershipException("TEAM_ACCESS_DENIED", "You are not a member of this team.");
    }

    private void requireTeamManager(UUID teamId, Authentication authentication) {
        String role = currentTeamRole(teamId, authentication);
        if (!canManage(role)) throw new TeamMembershipException("TEAM_MANAGE_ACCESS_DENIED", "Only the team owner, manager or captain can manage players.");
    }

    private String currentTeamRole(UUID teamId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        Integer owner = jdbc.queryForObject("SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id WHERE t.id = ? AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)", Integer.class, teamId, authentication.getName(), authentication.getName());
        if (owner != null && owner > 0) return "OWNER";
        String role = jdbc.query("SELECT tm.role FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)", rs -> rs.next() ? rs.getString(1) : null, teamId, authentication.getName(), authentication.getName());
        if (role == null) throw new TeamMembershipException("TEAM_ACCESS_DENIED", "You are not a member of this team.");
        return role;
    }

    private boolean canManage(String role) { return "OWNER".equals(role) || "MANAGER".equals(role) || "CAPTAIN".equals(role); }

    private MemberView member(UUID teamId, UUID playerId) {
        return jdbc.queryForObject("SELECT tm.team_id, p.id AS player_id, u.id AS user_id, u.full_name, u.email, u.phone, tm.role FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? AND p.id = ?", (rs, row) -> new MemberView(rs.getObject("team_id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"), rs.getString("role")), teamId, playerId);
    }

    private String normalizeRole(String role) {
        String normalized = role == null || role.isBlank() ? "PLAYER" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PLAYER", "MANAGER", "CAPTAIN", "VICE_CAPTAIN").contains(normalized)) throw new TeamMembershipException("INVALID_TEAM_ROLE", "Invalid team role. Use PLAYER, MANAGER, CAPTAIN or VICE_CAPTAIN.");
        return normalized;
    }

    public record AddMemberRequest(String email, UUID playerId, String role) {}
    public record ChangeRoleRequest(@NotBlank String role) {}
    public record MemberView(UUID teamId, UUID playerId, UUID userId, String fullName, String email, String phone, String role) {}
    public record PlayerSearchView(UUID playerId, UUID userId, String fullName, String email, String phone) {}
    public record TeamAccess(UUID teamId, String role, boolean canManage) {}

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class TeamMembershipException extends RuntimeException {
        private final String code;
        public TeamMembershipException(String code, String message) { super(message); this.code = code; }
        public String getCode() { return code; }
        public String getMessageCode() { return code; }
    }
}
