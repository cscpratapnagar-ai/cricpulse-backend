package com.cricket.platform.player.service.impl;

import com.cricket.platform.player.CreatePlayer;
import com.cricket.platform.player.GetPlayerProfile;
import com.cricket.platform.player.dto.PlayerRequest;
import com.cricket.platform.player.dto.PlayerResponse;
import com.cricket.platform.player.service.PlayerProfileService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PlayerProfileServiceImpl implements PlayerProfileService {

    private final CreatePlayer createPlayer;
    private final GetPlayerProfile getPlayerProfile;

    public PlayerProfileServiceImpl(CreatePlayer createPlayer, GetPlayerProfile getPlayerProfile) {
        this.createPlayer = createPlayer;
        this.getPlayerProfile = getPlayerProfile;
    }

    @Override
    public PlayerResponse create(Authentication authentication, PlayerRequest request) {
        return createPlayer.create(authentication, request);
    }

    @Override
    public PlayerResponse update(Authentication authentication, PlayerRequest request) {
        return createPlayer.update(authentication, request);
    }

    @Override
    public PlayerResponse getCurrent(Authentication authentication) {
        return createPlayer.current(authentication);
    }

    @Override
    public PlayerResponse getById(UUID playerId) {
        return getPlayerProfile.get(playerId);
    }
}
