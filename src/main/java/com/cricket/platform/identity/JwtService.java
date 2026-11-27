package com.cricket.platform.identity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtService {
    public static final String ACCESS_COOKIE_NAME = "CRICPULSE_ACCESS";

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.expiration-seconds:86400}") long expirationSeconds) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("security.jwt.secret must be set and at least 32 characters");
        if (expirationSeconds <= 0)
            throw new IllegalArgumentException("security.jwt.expiration-seconds must be greater than 0");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String create(String subject, String role) {
        return create(subject, role, expirationSeconds);
    }

    public String create(String subject, String role, long lifetimeSeconds) {
        return create(subject, role, lifetimeSeconds, null);
    }

    public String create(String subject, String role, long lifetimeSeconds, String sessionId) {
        if (lifetimeSeconds <= 0) throw new IllegalArgumentException("JWT lifetime must be greater than 0");
        Instant now = Instant.now();
        var builder = Jwts.builder().subject(subject).id(UUID.randomUUID().toString())
                .claim("role", role).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(lifetimeSeconds)));
        if (sessionId != null && !sessionId.isBlank()) builder.claim("sid", sessionId);
        return builder.signWith(key).compact();
    }

    public long expirationSeconds() { return expirationSeconds; }

    public String subject(String token) { return claims(token).getSubject(); }

    public String role(String token) {
        Object role = claims(token).get("role");
        return role == null ? "PLAYER" : role.toString();
    }

    public String sessionId(String token) { return claims(token).get("sid", String.class); }

    public Instant expiresAt(String token) {
        Date expiration = claims(token).getExpiration();
        if (expiration == null) throw new IllegalArgumentException("JWT expiration is required");
        return expiration.toInstant();
    }

    private io.jsonwebtoken.Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
