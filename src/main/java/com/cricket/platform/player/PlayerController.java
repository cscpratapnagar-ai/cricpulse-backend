package com.cricket.platform.player;

import com.cricket.platform.player.dto.PlayerRequest;
import com.cricket.platform.player.dto.PlayerResponse;
import com.cricket.platform.player.dto.PlayerView;
import com.cricket.platform.player.repository.PlayerRepository;
import jakarta.validation.Valid;
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
    private final PlayerRepository playerRepository;

    public PlayerController(CreatePlayer createPlayer,
                            AddPlayerToTeam addPlayerToTeam,
                            GetPlayerStatistics getPlayerStatistics,
                            GetPlayerProfile getPlayerProfile,
                            PlayerRepository playerRepository) {
        this.createPlayer = createPlayer;
        this.addPlayerToTeam = addPlayerToTeam;
        this.getPlayerStatistics = getPlayerStatistics;
        this.getPlayerProfile = getPlayerProfile;
        this.playerRepository = playerRepository;
    }

    @PostMapping
    public PlayerResponse create(Authentication authentication, @Valid @RequestBody PlayerRequest request) {
        return createPlayer.create(authentication, request);
    }

    @GetMapping("/me")
    public PlayerResponse me(Authentication authentication) {
        return createPlayer.current(authentication);
    }

    @PutMapping("/me")
    public PlayerResponse update(Authentication authentication, @Valid @RequestBody PlayerRequest request) {
        return createPlayer.update(authentication, request);
    }

    @PostMapping("/teams/{teamId}")
    public void addToTeam(@PathVariable UUID teamId,
                          @Valid @RequestBody AddPlayerToTeam.Request request,
                          Authentication authentication) {
        addPlayerToTeam.execute(teamId, request, authentication.getName());
    }

    @GetMapping("/teams/{teamId}")
    public List<PlayerView> teamPlayers(@PathVariable UUID teamId) {
        return playerRepository.findByTeamId(teamId);
    }

    @GetMapping("/statistics")
    public List<GetPlayerStatistics.PlayerStatistics> statistics() {
        return getPlayerStatistics.all();
    }

    @GetMapping("/{playerId}/statistics")
    public GetPlayerStatistics.PlayerStatistics playerStatistics(@PathVariable UUID playerId) {
        return getPlayerStatistics.one(playerId);
    }

    @GetMapping("/{playerId}")
    public PlayerResponse profile(@PathVariable UUID playerId) {
        return getPlayerProfile.get(playerId);
    }
}
