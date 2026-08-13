package com.cricket.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
        this.jdbc = jdbc; this.encoder = encoder; this.jwt = jwt;
    }

    public AuthResponse execute(Request request) {
        Map<String, Object> user = jdbc.queryForMap("SELECT id, full_name, email, role, password_hash FROM users WHERE email = ?", request.email());
        if (!encoder.matches(request.password(), (String) user.get("password_hash")))
            throw new IllegalArgumentException("Invalid email or password");
        String role = (String) user.get("role");
        return new AuthResponse(jwt.create(request.email(), role), user.get("id").toString(), (String) user.get("full_name"), role);
    }

    public AuthResponse current(String email) {
        Map<String, Object> user = jdbc.queryForMap("SELECT id, full_name, email, role FROM users WHERE email = ?", email);
        String role = (String) user.get("role");
        return new AuthResponse(null, user.get("id").toString(), (String) user.get("full_name"), role);
    }

    public record Request(@Email @NotBlank String email, @NotBlank String password) {}
    public record AuthResponse(String accessToken, String userId, String fullName, String role) {}
}
