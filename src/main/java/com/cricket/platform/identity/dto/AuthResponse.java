package com.cricket.platform.identity.dto;

public record AuthResponse(
        String accessToken,
        String userId,
        String fullName,
        String email,
        String role
) {}
