package com.cricket.platform.player;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
public class GetPlayerProfile {
    private final JdbcTemplate jdbc;

    public GetPlayerProfile(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Profile get(UUID playerId) {
        return jdbc.queryForObject("""
                SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style,
                       p.date_of_birth, p.city, p.playing_role, p.jersey_number,
                       p.bio, p.profile_photo_url
                FROM players p
                JOIN users u ON u.id = p.user_id
                WHERE p.id = ?
                """, (rs, row) -> new Profile(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("batting_style"),
                rs.getString("bowling_style"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("city"),
                rs.getString("playing_role"),
                (Integer) rs.getObject("jersey_number"),
                rs.getString("bio"),
                rs.getString("profile_photo_url")), playerId);
    }

    public record Profile(UUID id, UUID userId, String name, String battingStyle,
                          String bowlingStyle, LocalDate dateOfBirth, String city,
                          String playingRole, Integer jerseyNumber, String bio,
                          String profilePhotoUrl) {}
}
