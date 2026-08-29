package com.cricket.platform.player.repository;

import com.cricket.platform.player.dto.PlayerResponse;
import com.cricket.platform.player.dto.PlayerView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlayerRepository {

    private final JdbcTemplate jdbc;

    public PlayerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findUserIdByEmail(String email) {
        return jdbc.query("SELECT id FROM users WHERE lower(email) = lower(?)",
                rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty(), email);
    }

    public boolean existsByUserId(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE user_id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    public void create(UUID id, UUID userId, String battingStyle, String bowlingStyle,
                       LocalDate dateOfBirth, String city, String playingRole,
                       Integer jerseyNumber, String bio, String profilePhotoUrl) {
        jdbc.update("""
                INSERT INTO players(id, user_id, batting_style, bowling_style, date_of_birth, city,
                                    playing_role, jersey_number, bio, profile_photo_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, battingStyle, bowlingStyle, dateOfBirth, city,
                playingRole, jerseyNumber, bio, profilePhotoUrl);
    }

    public boolean update(UUID userId, String battingStyle, String bowlingStyle,
                          LocalDate dateOfBirth, String city, String playingRole,
                          Integer jerseyNumber, String bio, String profilePhotoUrl) {
        return jdbc.update("""
                UPDATE players
                SET batting_style = ?, bowling_style = ?, date_of_birth = ?, city = ?,
                    playing_role = ?, jersey_number = ?, bio = ?, profile_photo_url = ?
                WHERE user_id = ?
                """, battingStyle, bowlingStyle, dateOfBirth, city, playingRole,
                jerseyNumber, bio, profilePhotoUrl, userId) > 0;
    }

    public Optional<PlayerResponse> findProfileByUserId(UUID userId) {
        return jdbc.query("""
                SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style, p.date_of_birth,
                       p.city, p.playing_role, p.jersey_number, p.bio, p.profile_photo_url
                FROM players p JOIN users u ON u.id = p.user_id WHERE p.user_id = ?
                """, (rs, row) -> mapProfile(rs), userId).stream().findFirst();
    }

    public Optional<PlayerResponse> findProfileByPlayerId(UUID playerId) {
        return jdbc.query("""
                SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style, p.date_of_birth,
                       p.city, p.playing_role, p.jersey_number, p.bio, p.profile_photo_url
                FROM players p JOIN users u ON u.id = p.user_id WHERE p.id = ?
                """, (rs, row) -> mapProfile(rs), playerId).stream().findFirst();
    }

    public List<PlayerView> findByTeamId(UUID teamId) {
        return jdbc.query("""
                SELECT p.id, p.user_id, u.full_name, p.batting_style, p.bowling_style, tm.role
                FROM team_members tm
                JOIN players p ON p.id = tm.player_id
                JOIN users u ON u.id = p.user_id
                WHERE tm.team_id = ?
                ORDER BY u.full_name
                """, (rs, row) -> new PlayerView(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("batting_style"),
                rs.getString("bowling_style"),
                rs.getString("role")), teamId);
    }

    private PlayerResponse mapProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlayerResponse(
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
                rs.getString("profile_photo_url"));
    }
}
