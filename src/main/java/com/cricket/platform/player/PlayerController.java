package com.cricket.platform.player;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/players")
public class PlayerController {
    private final CreatePlayer createPlayer;
    private final AddPlayerToTeam addPlayerToTeam;
    private final GetPlayerStatistics getPlayerStatistics;
    private final GetPlayerProfile getPlayerProfile;
    private final GetPlayerPerformanceHistory getPlayerPerformanceHistory;
    private final GetPlayerIntelligence getPlayerIntelligence;
    private final JdbcTemplate jdbc;
    private final ComparePlayers comparePlayers;

    public PlayerController(CreatePlayer createPlayer, AddPlayerToTeam addPlayerToTeam,
                            GetPlayerStatistics getPlayerStatistics, GetPlayerProfile getPlayerProfile,
                            GetPlayerPerformanceHistory getPlayerPerformanceHistory,
                            GetPlayerIntelligence getPlayerIntelligence, JdbcTemplate jdbc,
                            ComparePlayers comparePlayers) {
        this.createPlayer = createPlayer;
        this.addPlayerToTeam = addPlayerToTeam;
        this.getPlayerStatistics = getPlayerStatistics;
        this.getPlayerProfile = getPlayerProfile;
        this.getPlayerPerformanceHistory = getPlayerPerformanceHistory;
        this.getPlayerIntelligence = getPlayerIntelligence;
        this.jdbc = jdbc;
        this.comparePlayers = comparePlayers;
    }

    @PostMapping
    CreatePlayer.PlayerResponse create(Authentication authentication, @Valid @RequestBody CreatePlayer.Request request) { return createPlayer.create(authentication, request); }

    @GetMapping("/me")
    CreatePlayer.PlayerResponse me(Authentication authentication) { return createPlayer.current(authentication); }

    @PutMapping("/me")
    CreatePlayer.PlayerResponse update(Authentication authentication, @Valid @RequestBody CreatePlayer.Request request) { return createPlayer.update(authentication, request); }

    @PostMapping("/teams/{teamId}")
    void addToTeam(@PathVariable UUID teamId, @Valid @RequestBody AddPlayerToTeam.Request request, Authentication authentication) { addPlayerToTeam.execute(teamId, request, authentication.getName()); }

    @GetMapping("/teams/{teamId}")
    List<PlayerView> teamPlayers(@PathVariable UUID teamId, Authentication authentication) {
        requireTeamAccess(teamId, authentication);
        return jdbc.query("SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style, tm.role FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? ORDER BY u.full_name", (rs, row) -> new PlayerView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("batting_style"), rs.getString("bowling_style"), rs.getString("role")), teamId);
    }

    @GetMapping("/statistics")
    List<GetPlayerStatistics.PlayerStatistics> statistics(Authentication authentication) { return List.of(getPlayerStatistics.one(currentPlayerId(authentication))); }

    @GetMapping("/compare")
    ComparePlayers.Comparison compare(@RequestParam UUID left, @RequestParam UUID right, Authentication authentication) {
        requirePlayerAccess(left, authentication);
        requirePlayerAccess(right, authentication);
        return comparePlayers.compare(left, right);
    }

    @GetMapping("/{playerId}/statistics")
    GetPlayerStatistics.PlayerStatistics playerStatistics(@PathVariable UUID playerId, Authentication authentication) { requirePlayerAccess(playerId, authentication); return getPlayerStatistics.one(playerId); }

    @GetMapping("/{playerId}/recent-matches")
    List<GetPlayerPerformanceHistory.MatchPerformance> recentMatches(@PathVariable UUID playerId, @RequestParam(defaultValue = "10") int limit, Authentication authentication) { requirePlayerAccess(playerId, authentication); return getPlayerPerformanceHistory.recent(playerId, limit); }

    @GetMapping("/{playerId}/performance-trend")
    GetPlayerPerformanceHistory.PerformanceTrend performanceTrend(@PathVariable UUID playerId, @RequestParam(defaultValue = "10") int limit, Authentication authentication) { requirePlayerAccess(playerId, authentication); return getPlayerPerformanceHistory.trend(playerId, limit); }

    @GetMapping("/{playerId}/intelligence")
    GetPlayerIntelligence.Intelligence intelligence(@PathVariable UUID playerId, @RequestParam(defaultValue = "10") int matches, Authentication authentication) { requirePlayerAccess(playerId, authentication); return getPlayerIntelligence.get(playerId, matches); }

    @GetMapping("/{playerId}")
    GetPlayerProfile.Profile profile(@PathVariable UUID playerId, Authentication authentication) { requirePlayerAccess(playerId, authentication); return getPlayerProfile.get(playerId); }

    private UUID currentPlayerId(Authentication authentication) {
        return jdbc.queryForObject("SELECT p.id FROM players p JOIN users u ON u.id = p.user_id WHERE LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?", UUID.class, authentication.getName(), authentication.getName());
    }

    private void requirePlayerAccess(UUID playerId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication is required.");
        UUID current = currentPlayerId(authentication);
        if (current.equals(playerId)) return;
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM team_members tm WHERE tm.player_id = ? AND EXISTS (SELECT 1 FROM team_members own_tm WHERE own_tm.team_id = tm.team_id AND own_tm.player_id = ?)", Integer.class, playerId, current);
        if (member == null || member == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "You do not have access to this player.");
    }

    private void requireTeamAccess(UUID teamId, Authentication authentication) {
        UUID current = currentPlayerId(authentication);
        Integer allowed = jdbc.queryForObject("SELECT COUNT(*) FROM teams t WHERE t.id = ? AND (t.owner_id = (SELECT id FROM users WHERE LOWER(TRIM(email)) = LOWER(TRIM(?)) OR CAST(id AS TEXT) = ?) OR EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.player_id = ?))", Integer.class, teamId, authentication.getName(), authentication.getName(), current);
        if (allowed == null || allowed == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "You do not have access to this team.");
    }

    public record PlayerView(UUID id, UUID userId, String name, String battingStyle, String bowlingStyle, String role) {}
}
