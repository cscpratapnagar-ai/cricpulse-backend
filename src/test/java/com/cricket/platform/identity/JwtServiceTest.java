package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;

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
    void rejectsNonPositiveTicketLifetime() {
        assertThrows(IllegalArgumentException.class,
                () -> jwt.create("scorer@example.com", "SCORER", 0));
    }
}
