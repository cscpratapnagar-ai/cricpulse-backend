package com.cricket.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

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
        UUID id = UUID.randomUUID();
        // Public registration can never self-assign a privileged role.
        // ADMIN, CAPTAIN and SCORER must be granted through protected flows.
        String role = "PLAYER";
        if (request.password() == null || request.password().length() < 8)
            throw new IllegalArgumentException("Password must contain at least 8 characters");
        jdbc.update("INSERT INTO users(id, full_name, email, phone, role, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                id, request.fullName(), request.email(), request.phone(), role, passwordEncoder.encode(request.password()));
        return new UserResponse(id, request.fullName(), role);
    }

    public record Request(@NotBlank String fullName, @Email String email, String phone, String role, String password) {}
    public record UserResponse(UUID id, String fullName, String role) {}
}
