package com.cricket.platform.player;

import com.cricket.platform.player.dto.PlayerRequest;
import com.cricket.platform.player.dto.PlayerResponse;
import com.cricket.platform.player.dto.PlayerView;
import com.cricket.platform.player.service.PlayerProfileService;
import com.cricket.platform.player.service.PlayerStatisticsService;
import com.cricket.platform.player.service.PlayerTeamService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerProfileService playerProfileService;
    private final PlayerTeamService playerTeamService;
    private final PlayerStatisticsService playerStatisticsService;

    public PlayerController(
            PlayerProfileService playerProfileService,
            PlayerTeamService playerTeamService,
            PlayerStatisticsService playerStatisticsService) {
        this.playerProfileService = playerProfileService;
        this.playerTeamService = playerTeamService;
        this.playerStatisticsService = playerStatisticsService;
    }

    @PostMapping
    public PlayerResponse create(Authentication authentication, @Valid @RequestBody PlayerRequest request) {
        return playerProfileService.create(authentication, request);
    }

    @GetMapping("/me")
    public PlayerResponse me(Authentication authentication) {
        return playerProfileService.getCurrent(authentication);
    }

    @PutMapping("/me")
    public PlayerResponse update(Authentication authentication, @Valid @RequestBody PlayerRequest request) {
        return playerProfileService.update(authentication, request);
    }

    @PostMapping("/teams/{teamId}")
    public void addToTeam(
            @PathVariable UUID teamId,
            @Valid @RequestBody AddPlayerToTeam.Request request,
            Authentication authentication) {
        playerTeamService.addPlayer(teamId, request, authentication.getName());
    }

    @GetMapping("/teams/{teamId}")
    public List<PlayerView> teamPlayers(@PathVariable UUID teamId) {
        return playerTeamService.getTeamPlayers(teamId);
    }

    @GetMapping("/statistics")
    public List<GetPlayerStatistics.PlayerStatistics> statistics() {
        return playerStatisticsService.getAll();
    }

    @GetMapping("/{playerId}/statistics")
    public GetPlayerStatistics.PlayerStatistics playerStatistics(@PathVariable UUID playerId) {
        return playerStatisticsService.getByPlayerId(playerId);
    }

    @GetMapping("/{playerId}")
    public PlayerResponse profile(@PathVariable UUID playerId) {
        return playerProfileService.getById(playerId);
    }
}
