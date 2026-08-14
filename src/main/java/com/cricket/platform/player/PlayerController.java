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
    private final JdbcTemplate jdbc;

    public PlayerController(CreatePlayer createPlayer, AddPlayerToTeam addPlayerToTeam, JdbcTemplate jdbc) {
        this.createPlayer = createPlayer;
        this.addPlayerToTeam = addPlayerToTeam;
        this.jdbc = jdbc;
    }

    @PostMapping
    CreatePlayer.PlayerResponse create(Authentication authentication, @Valid @RequestBody CreatePlayer.Request request) {
        return createPlayer.create(authentication, request);
    }

    @GetMapping("/me")
    CreatePlayer.PlayerResponse me(Authentication authentication) {
        return createPlayer.current(authentication);
    }

    @PutMapping("/me")
    CreatePlayer.PlayerResponse update(Authentication authentication, @Valid @RequestBody CreatePlayer.Request request) {
        return createPlayer.update(authentication, request);
    }

    @PostMapping("/teams/{teamId}")
    void addToTeam(@PathVariable UUID teamId, @Valid @RequestBody AddPlayerToTeam.Request request) {
        addPlayerToTeam.execute(teamId, request);
    }

    @GetMapping("/teams/{teamId}")
    List<PlayerView> teamPlayers(@PathVariable UUID teamId) {
        return jdbc.query("SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style, tm.role FROM team_members tm JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id WHERE tm.team_id = ? ORDER BY u.full_name",
                (rs, row) -> new PlayerView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("batting_style"), rs.getString("bowling_style"), rs.getString("role")), teamId);
    }

    public record PlayerView(UUID id, UUID userId, String name, String battingStyle, String bowlingStyle, String role) {}
}
