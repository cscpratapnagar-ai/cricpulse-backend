package com.cricket.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LoginUser {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public LoginUser(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public AuthResponse execute(Request request) {
        String email = request.email().trim().toLowerCase();
        Map<String, Object> user;
        try {
            user = jdbc.queryForMap(
                    "SELECT id, full_name, email, role, password_hash FROM users WHERE lower(email) = lower(?)",
                    email);
        } catch (EmptyResultDataAccessException ex) {
            throw new InvalidCredentialsException();
        }

        if (!encoder.matches(request.password(), (String) user.get("password_hash"))) {
            throw new InvalidCredentialsException();
        }

        String role = (String) user.get("role");
        return new AuthResponse(
                jwt.create(email, role),
                user.get("id").toString(),
                (String) user.get("full_name"),
                (String) user.get("email"),
                role);
    }

    public AuthResponse current(String email) {
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, full_name, email, role FROM users WHERE lower(email) = lower(?)", email);
        String role = (String) user.get("role");
        return new AuthResponse(null, user.get("id").toString(),
                (String) user.get("full_name"), (String) user.get("email"), role);
    }

    public record Request(@Email @NotBlank String email, @NotBlank String password) {}

    public record AuthResponse(String accessToken, String userId, String fullName,
                               String email, String role) {}

    public static final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid email or password");
        }
    }
}
