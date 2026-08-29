package com.cricket.platform.identity.repository;

import com.cricket.platform.identity.repository.IdentityRepository.UserRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class IdentityRepository {

    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> findByEmailWithPassword(String email) {
        return jdbc.query(
                "SELECT id, full_name, email, phone, role, password_hash FROM users WHERE lower(email) = lower(?)",
                (rs, rowNum) -> new UserRecord(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getString("password_hash")),
                email
        ).stream().findFirst();
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbc.query(
                "SELECT id, full_name, email, phone, role, password_hash FROM users WHERE lower(email) = lower(?)",
                (rs, rowNum) -> new UserRecord(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getString("password_hash")),
                email
        ).stream().findFirst();
    }

    public boolean existsByEmail(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(?))",
                Boolean.class, email));
    }

    public boolean existsByPhone(String phone) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE phone = ?)",
                Boolean.class, phone));
    }

    public void createUser(UUID id, String fullName, String email, String phone,
                           String role, String passwordHash) {
        jdbc.update(
                "INSERT INTO users(id, full_name, email, phone, role, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                id, fullName, email, phone, role, passwordHash);
    }

    public void createPlayerProfile(UUID userId) {
        jdbc.update("INSERT INTO players(id, user_id) VALUES (?, ?)", UUID.randomUUID(), userId);
    }

    public record UserRecord(
            UUID id,
            String fullName,
            String email,
            String phone,
            String role,
            String passwordHash
    ) {}
}
