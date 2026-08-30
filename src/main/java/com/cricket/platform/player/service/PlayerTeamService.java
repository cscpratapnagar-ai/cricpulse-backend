package com.cricket.platform.player.service;

import com.cricket.platform.player.AddPlayerToTeam;
import com.cricket.platform.player.dto.PlayerView;

import java.util.List;
import java.util.UUID;

public interface PlayerTeamService {
    void addPlayer(UUID teamId, AddPlayerToTeam.Request request, String authenticatedEmail);
    List<PlayerView> getTeamPlayers(UUID teamId);
}
