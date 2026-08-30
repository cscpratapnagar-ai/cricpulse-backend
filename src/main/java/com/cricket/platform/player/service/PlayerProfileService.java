package com.cricket.platform.player.service;

import com.cricket.platform.player.dto.PlayerRequest;
import com.cricket.platform.player.dto.PlayerResponse;
import org.springframework.security.core.Authentication;

import java.util.UUID;

public interface PlayerProfileService {
    PlayerResponse create(Authentication authentication, PlayerRequest request);
    PlayerResponse update(Authentication authentication, PlayerRequest request);
    PlayerResponse getCurrent(Authentication authentication);
    PlayerResponse getById(UUID playerId);
}
