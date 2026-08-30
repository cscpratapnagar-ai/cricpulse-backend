package com.cricket.platform.player.service.impl;

import com.cricket.platform.player.AddPlayerToTeam;
import com.cricket.platform.player.dto.PlayerView;
import com.cricket.platform.player.repository.PlayerRepository;
import com.cricket.platform.player.service.PlayerTeamService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PlayerTeamServiceImpl implements PlayerTeamService {

    private final AddPlayerToTeam addPlayerToTeam;
    private final PlayerRepository playerRepository;

    public PlayerTeamServiceImpl(AddPlayerToTeam addPlayerToTeam, PlayerRepository playerRepository) {
        this.addPlayerToTeam = addPlayerToTeam;
        this.playerRepository = playerRepository;
    }

    @Override
    public void addPlayer(UUID teamId, AddPlayerToTeam.Request request, String authenticatedEmail) {
        addPlayerToTeam.execute(teamId, request, authenticatedEmail);
    }

    @Override
    public List<PlayerView> getTeamPlayers(UUID teamId) {
        return playerRepository.findByTeamId(teamId);
    }
}
