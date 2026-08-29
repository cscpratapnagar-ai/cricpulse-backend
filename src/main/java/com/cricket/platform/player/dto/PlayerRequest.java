package com.cricket.platform.player.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record PlayerRequest(
        String battingStyle,
        String bowlingStyle,
        LocalDate dateOfBirth,
        String city,
        String playingRole,
        @Min(0) @Max(99) Integer jerseyNumber,
        String bio,
        String profilePhotoUrl
) {}
