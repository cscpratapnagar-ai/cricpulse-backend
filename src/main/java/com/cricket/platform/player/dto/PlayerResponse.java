package com.cricket.platform.player.dto;

import java.time.LocalDate;
import java.util.UUID;

public record PlayerResponse(
        UUID id,
        UUID userId,
        String name,
        String battingStyle,
        String bowlingStyle,
        LocalDate dateOfBirth,
        String city,
        String playingRole,
        Integer jerseyNumber,
        String bio,
        String profilePhotoUrl
) {}
