package com.cricket.platform.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AccessTokenRevocationService {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;

    public AccessTokenRevocationService(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        Instant expiresAt = jwt.expiresAt(token);
        // Opportunistically remove expired fingerprints so the revocation table stays bounded.
        jdbc.update("DELETE FROM revoked_access_tokens WHERE expires_at <= now()");
        jdbc.update("""
                INSERT INTO revoked_access_tokens(token_fingerprint, expires_at)
                VALUES (?, ?)
                ON CONFLICT (token_fingerprint) DO NOTHING
                """, fingerprint(token), Timestamp.from(expiresAt));
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM revoked_access_tokens WHERE token_fingerprint = ? AND expires_at > now())",
                Boolean.class, fingerprint(token)));
    }

    private String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
