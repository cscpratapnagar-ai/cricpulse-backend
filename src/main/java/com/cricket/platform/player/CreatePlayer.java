package com.cricket.platform.player;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Component
public class CreatePlayer {
    private final JdbcTemplate jdbc;

    public CreatePlayer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public PlayerResponse create(Authentication authentication, Request request) {
        UUID userId = userId(authentication);
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE user_id = ?", Integer.class, userId);
        if (existing != null && existing > 0) {
            throw new IllegalStateException("Player profile already exists");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO players(id, user_id, batting_style, bowling_style, date_of_birth, city, playing_role, jersey_number, bio, profile_photo_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, request.battingStyle(), request.bowlingStyle(), request.dateOfBirth(),
                request.city(), request.playingRole(), request.jerseyNumber(), request.bio(), request.profilePhotoUrl());
        return findByUserId(userId);
    }

    public PlayerResponse update(Authentication authentication, Request request) {
        UUID userId = userId(authentication);
        int updated = jdbc.update("""
                UPDATE players SET batting_style = ?, bowling_style = ?, date_of_birth = ?, city = ?,
                playing_role = ?, jersey_number = ?, bio = ?, profile_photo_url = ? WHERE user_id = ?
                """, request.battingStyle(), request.bowlingStyle(), request.dateOfBirth(), request.city(),
                request.playingRole(), request.jerseyNumber(), request.bio(), request.profilePhotoUrl(), userId);
        if (updated == 0) throw new IllegalStateException("Player profile was not found");
        return findByUserId(userId);
    }

    public PlayerResponse current(Authentication authentication) {
        return findByUserId(userId(authentication));
    }

    private PlayerResponse findByUserId(UUID userId) {
        return jdbc.queryForObject("""
                SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style, p.date_of_birth,
                p.city, p.playing_role, p.jersey_number, p.bio, p.profile_photo_url
                FROM players p JOIN users u ON u.id = p.user_id WHERE p.user_id = ?
                """, (rs, row) -> new PlayerResponse(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                rs.getString("batting_style"), rs.getString("bowling_style"), rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("city"), rs.getString("playing_role"),
                (Integer) rs.getObject("jersey_number"), rs.getString("bio"), rs.getString("profile_photo_url")), userId);
    }

    private UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw new IllegalStateException("Authentication required");
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, authentication.getName());
    }

    public record Request(
            String battingStyle,
            String bowlingStyle,
            LocalDate dateOfBirth,
            String city,
            String playingRole,
            @Min(0) @Max(99) Integer jerseyNumber,
            String bio,
            String profilePhotoUrl) {}

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
            String profilePhotoUrl) {}
}
