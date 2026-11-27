package com.cricket.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class LoginUser {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final SessionRegistryService sessions;

    @Autowired
    public LoginUser(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, SessionRegistryService sessions) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.sessions = sessions;
    }

    // Kept for isolated unit tests that do not load the session registry bean.
    LoginUser(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.sessions = null;
    }

    public AuthResponse execute(Request request) {
        return execute(request, null, null);
    }

    public AuthResponse execute(Request request, String userAgent, String ipAddress) {
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
        String token;
        if (sessions == null) {
            token = jwt.create(email, role);
        } else {
            String sessionId = UUID.randomUUID().toString();
            Instant expiresAt = Instant.now().plusSeconds(jwt.expirationSeconds());
            sessions.create((UUID) user.get("id"), sessionId, expiresAt, userAgent, ipAddress);
            token = jwt.create(email, role, jwt.expirationSeconds(), sessionId);
        }
        return new AuthResponse(token, user.get("id").toString(),
                (String) user.get("full_name"), (String) user.get("email"), role);
    }

    public AuthResponse current(String email) {
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, full_name, email, role FROM users WHERE lower(email) = lower(?)", email);
        String role = (String) user.get("role");
        return new AuthResponse(null, user.get("id").toString(),
                (String) user.get("full_name"), (String) user.get("email"), role);
    }

    public record Request(@Email @NotBlank String email, @NotBlank String password) {}
    public record AuthResponse(String accessToken, String userId, String fullName, String email, String role) {}

    public static final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() { super("Invalid email or password"); }
    }
}
