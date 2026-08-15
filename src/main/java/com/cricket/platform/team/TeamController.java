package com.cricket.platform.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
    CreateTeam.TeamResponse create(
            @Valid @RequestBody CreateTeam.Request request,
            Authentication authentication
    ) {
        return createTeam.execute(request, authentication.getName());
    }

    @GetMapping("/mine")
    List<GetTeam.TeamView> mine(Authentication authentication) {
        return jdbc.query(
                "SELECT t.id, t.name, t.city, t.owner_id " +
                "FROM teams t JOIN users u ON u.id = t.owner_id " +
                "WHERE LOWER(u.email) = LOWER(?) ORDER BY t.name",
                (rs, row) -> new GetTeam.TeamView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getObject("owner_id", UUID.class)
                ),
                authentication.getName()
        );
    }

    @GetMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/members")
    List<MemberView> members(@PathVariable UUID id, Authentication authentication) {
        requireTeamOwner(id, authentication);
        return jdbc.query(
                "SELECT tm.team_id, p.id AS player_id, u.id AS user_id, u.full_name, u.email, u.phone, tm.role " +
                "FROM team_members tm " +
                "JOIN players p ON p.id = tm.player_id " +
                "JOIN users u ON u.id = p.user_id " +
                "WHERE tm.team_id = ? ORDER BY CASE tm.role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 WHEN 'CAPTAIN' THEN 2 WHEN 'VICE_CAPTAIN' THEN 3 ELSE 4 END, u.full_name",
                (rs, row) -> new MemberView(
                        rs.getObject("team_id", UUID.class),
                        rs.getObject("player_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role")
                ),
                id
        );
    }

    @PostMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/members")
    MemberView addMember(
            @PathVariable UUID id,
            @Valid @RequestBody AddMemberRequest request,
            Authentication authentication
    ) {
        requireTeamOwner(id, authentication);
        String role = normalizeRole(request.role());
        if ("OWNER".equals(role)) {
            throw new TeamMembershipException("OWNER_ROLE_NOT_ASSIGNABLE", "The team owner is assigned automatically when the team is created.");
        }

        UUID playerId = jdbc.query(
                "SELECT p.id FROM players p JOIN users u ON u.id = p.user_id WHERE LOWER(u.email) = LOWER(?)",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                request.email().trim()
        );
        if (playerId == null) {
            throw new TeamMembershipException("PLAYER_NOT_FOUND", "No registered player profile was found for this email.");
        }

        Integer alreadyMember = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ? AND player_id = ?",
                Integer.class, id, playerId
        );
        if (alreadyMember != null && alreadyMember > 0) {
            throw new TeamMembershipException("PLAYER_ALREADY_IN_TEAM", "This player is already a member of this team.");
        }

        jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, ?)", id, playerId, role);
        return member(id, playerId);
    }

    @PatchMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/members/{playerId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    MemberView changeRole(
            @PathVariable UUID id,
            @PathVariable UUID playerId,
            @Valid @RequestBody ChangeRoleRequest request,
            Authentication authentication
    ) {
        requireTeamOwner(id, authentication);
        String role = normalizeRole(request.role());
        if ("OWNER".equals(role)) {
            throw new TeamMembershipException("OWNER_ROLE_NOT_ASSIGNABLE", "The team owner cannot be reassigned through squad role management.");
        }
        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ? AND player_id = ?",
                Integer.class, id, playerId
        );
        if (memberCount == null || memberCount == 0) {
            throw new TeamMembershipException("TEAM_MEMBER_NOT_FOUND", "This player is not a member of the team.");
        }
        jdbc.update("UPDATE team_members SET role = ? WHERE team_id = ? AND player_id = ?", role, id, playerId);
        return member(id, playerId);
    }

    @DeleteMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/members/{playerId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    void removeMember(
            @PathVariable UUID id,
            @PathVariable UUID playerId,
            Authentication authentication
    ) {
        requireTeamOwner(id, authentication);
        String role = jdbc.query(
                "SELECT role FROM team_members WHERE team_id = ? AND player_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                id, playerId
        );
        if (role == null) {
            throw new TeamMembershipException("TEAM_MEMBER_NOT_FOUND", "This player is not a member of the team.");
        }
        if ("OWNER".equals(role)) {
            throw new TeamMembershipException("OWNER_CANNOT_BE_REMOVED", "The team owner cannot be removed from the team.");
        }
        jdbc.update("DELETE FROM team_members WHERE team_id = ? AND player_id = ?", id, playerId);
    }

    @GetMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    GetTeam.TeamView get(@PathVariable UUID id) { return getTeam.execute(id); }

    @GetMapping
    List<GetTeam.TeamView> list() {
        return jdbc.query("SELECT id, name, city, owner_id FROM teams ORDER BY name",
                (rs, row) -> new GetTeam.TeamView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getObject("owner_id", UUID.class)
                ));
    }

    private void requireTeamOwner(UUID teamId, Authentication authentication) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id WHERE t.id = ? AND LOWER(u.email) = LOWER(?)",
                Integer.class, teamId, authentication.getName()
        );
        if (count == null || count == 0) {
            throw new TeamMembershipException("TEAM_ACCESS_DENIED", "Only the team owner can manage this team.");
        }
    }

    private MemberView member(UUID teamId, UUID playerId) {
        return jdbc.queryForObject(
                "SELECT tm.team_id, p.id AS player_id, u.id AS user_id, u.full_name, u.email, u.phone, tm.role " +
                "FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id " +
                "WHERE tm.team_id = ? AND p.id = ?",
                (rs, row) -> new MemberView(
                        rs.getObject("team_id", UUID.class), rs.getObject("player_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getString("role")
                ), id(teamId), id(playerId)
        );
    }

    private UUID id(UUID value) { return value; }

    private String normalizeRole(String role) {
        String normalized = role == null || role.isBlank() ? "PLAYER" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PLAYER", "MANAGER", "CAPTAIN", "VICE_CAPTAIN").contains(normalized)) {
            throw new TeamMembershipException("INVALID_TEAM_ROLE", "Invalid team role. Use PLAYER, MANAGER, CAPTAIN or VICE_CAPTAIN.");
        }
        return normalized;
    }

    public record AddMemberRequest(@NotBlank String email, String role) {}
    public record ChangeRoleRequest(@NotBlank String role) {}
    public record MemberView(UUID teamId, UUID playerId, UUID userId, String fullName, String email, String phone, String role) {}

    public static class TeamMembershipException extends RuntimeException {
        private final String code;
        public TeamMembershipException(String code, String message) { super(message); this.code = code; }
        public String getCode() { return code; }
    }
}
