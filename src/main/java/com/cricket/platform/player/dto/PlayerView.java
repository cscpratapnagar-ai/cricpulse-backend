package com.cricket.platform.player.dto;

import java.util.UUID;

public record PlayerView(
        UUID id,
        UUID userId,
        String name,
        String battingStyle,
        String bowlingStyle,
        String role
) {}
