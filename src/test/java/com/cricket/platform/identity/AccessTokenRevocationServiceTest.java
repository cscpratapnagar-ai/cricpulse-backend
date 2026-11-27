package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessTokenRevocationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JwtService jwt = new JwtService("01234567890123456789012345678901", 86400);
    private final AccessTokenRevocationService revocations = new AccessTokenRevocationService(jdbc, jwt);

    @Test
    void storesOnlyFingerprintAndExpiryWhenRevokingToken() {
        String token = jwt.create("scorer@example.com", "SCORER", 60);

        revocations.revoke(token);

        verify(jdbc).update(contains("INSERT INTO revoked_access_tokens"), anyString(), any(Timestamp.class));
    }

    @Test
    void reportsRevokedTokenByFingerprint() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        String token = jwt.create("scorer@example.com", "SCORER", 60);

        assertTrue(revocations.isRevoked(token));
        verify(jdbc).queryForObject(contains("SELECT EXISTS"), eq(Boolean.class), anyString());
    }

    @Test
    void treatsMissingTokenAsNotRevoked() {
        assertFalse(revocations.isRevoked(null));
        verifyNoInteractions(jdbc);
    }
}
