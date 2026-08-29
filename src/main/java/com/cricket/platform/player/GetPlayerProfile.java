package com.cricket.platform.player;

import com.cricket.platform.player.dto.PlayerResponse;
import com.cricket.platform.player.repository.PlayerRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GetPlayerProfile {

    private final PlayerRepository playerRepository;

    public GetPlayerProfile(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public PlayerResponse get(UUID playerId) {
        return playerRepository.findProfileByPlayerId(playerId)
                .orElseThrow(CreatePlayer.PlayerProfileNotFoundException::new);
    }
}
