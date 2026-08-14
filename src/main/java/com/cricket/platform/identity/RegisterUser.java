package com.cricket.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RegisterUser {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public RegisterUser(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse execute(Request request) {
        String email = request.email().trim().toLowerCase();
        String fullName = request.fullName().trim();
        String phone = request.phone() == null ? null : request.phone().trim();

        if (request.password() == null || request.password().length() < 8) {
            throw new IllegalArgumentException("Password must contain at least 8 characters");
        }
        if (fullName.length() < 2) {
            throw new IllegalArgumentException("Full name must contain at least 2 characters");
        }
        if (phone != null && !phone.isBlank() && !phone.matches("^[+]?[0-9][0-9 -]{7,14}$")) {
            throw new IllegalArgumentException("Please enter a valid phone number");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(?))", Boolean.class, email))) {
            throw new DuplicateKeyException("An account with this email already exists");
        }

        UUID id = UUID.randomUUID();
        // Public registration can never self-assign a privileged role.
        // ADMIN, CAPTAIN and SCORER must be granted through protected flows.
        String role = "PLAYER";
        jdbc.update(
                "INSERT INTO users(id, full_name, email, phone, role, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                id, fullName, email, phone, role, passwordEncoder.encode(request.password()));

        return new UserResponse(id, fullName, email, role);
    }

    public record Request(@NotBlank String fullName, @Email @NotBlank String email,
                          String phone, String role, String password) {}

    public record UserResponse(UUID id, String fullName, String email, String role) {}
}
