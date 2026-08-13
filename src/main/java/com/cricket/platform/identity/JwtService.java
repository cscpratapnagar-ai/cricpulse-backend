package com.cricket.platform.identity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {
    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.expiration-seconds:86400}") long expirationSeconds) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("security.jwt.secret must be at least 32 characters");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String create(String subject, String role) {
        Instant now = Instant.now();
        return Jwts.builder().subject(subject).claim("role", role)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key).compact();
    }

    public String subject(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }

    public String role(String token) {
        Object role = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().get("role");
        return role == null ? "PLAYER" : role.toString();
    }
}
