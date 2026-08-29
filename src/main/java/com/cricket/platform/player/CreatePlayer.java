package com.cricket.platform.player;

import com.cricket.platform.player.dto.PlayerRequest;
import com.cricket.platform.player.dto.PlayerResponse;
import com.cricket.platform.player.repository.PlayerRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CreatePlayer {

    private final PlayerRepository playerRepository;

    public CreatePlayer(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public PlayerResponse create(Authentication authentication, PlayerRequest request) {
        UUID userId = authenticatedUserId(authentication);
        if (playerRepository.existsByUserId(userId)) {
            throw new PlayerProfileAlreadyExistsException();
        }

        playerRepository.create(
                UUID.randomUUID(), userId,
                request.battingStyle(), request.bowlingStyle(), request.dateOfBirth(),
                request.city(), request.playingRole(), request.jerseyNumber(),
                request.bio(), request.profilePhotoUrl());

        return findByUserId(userId);
    }

    public PlayerResponse update(Authentication authentication, PlayerRequest request) {
        UUID userId = authenticatedUserId(authentication);
        boolean updated = playerRepository.update(
                userId, request.battingStyle(), request.bowlingStyle(), request.dateOfBirth(),
                request.city(), request.playingRole(), request.jerseyNumber(),
                request.bio(), request.profilePhotoUrl());

        if (!updated) {
            throw new PlayerProfileNotFoundException();
        }
        return findByUserId(userId);
    }

    public PlayerResponse current(Authentication authentication) {
        return findByUserId(authenticatedUserId(authentication));
    }

    private PlayerResponse findByUserId(UUID userId) {
        return playerRepository.findProfileByUserId(userId)
                .orElseThrow(PlayerProfileNotFoundException::new);
    }

    private UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Authentication required");
        }
        return playerRepository.findUserIdByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user was not found"));
    }

    public static final class PlayerProfileAlreadyExistsException extends RuntimeException {
        public PlayerProfileAlreadyExistsException() {
            super("Player profile already exists");
        }
    }

    public static final class PlayerProfileNotFoundException extends RuntimeException {
        public PlayerProfileNotFoundException() {
            super("Player profile was not found");
        }
    }
}
