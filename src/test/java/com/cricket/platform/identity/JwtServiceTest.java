package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final JwtService jwt = new JwtService("01234567890123456789012345678901", 86400);

    @Test
    void createsShortLivedWebSocketTicketWithSameIdentityAndRole() {
        String ticket = jwt.create("scorer@example.com", "SCORER", 60);

        assertEquals("scorer@example.com", jwt.subject(ticket));
        assertEquals("SCORER", jwt.role(ticket));
    }

    @Test
    void exposesValidatedExpirationForRevocation() {
        Instant before = Instant.now();
        String token = jwt.create("scorer@example.com", "SCORER", 60);

        Instant expiration = jwt.expiresAt(token);

        assertTrue(expiration.isAfter(before.plusSeconds(59)));
        assertTrue(expiration.isBefore(before.plusSeconds(61)));
    }

    @Test
    void rejectsNonPositiveTicketLifetime() {
        assertThrows(IllegalArgumentException.class,
                () -> jwt.create("scorer@example.com", "SCORER", 0));
    }
 
    @Test
    void createsDistinctTokensForSeparateSessionsEvenWithinSameSecond() {
        String first = jwt.create("scorer@example.com", "PLAYER");
        String second = jwt.create("scorer@example.com", "PLAYER");

        assertNotEquals(first, second);
    }
}
