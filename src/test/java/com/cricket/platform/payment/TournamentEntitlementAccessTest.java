package com.cricket.platform.payment;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TournamentEntitlementAccessTest {
    @Test
    void OrganizerProIncludesOrganizerPlus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);

        assertTrue(new TournamentEntitlementAccess(jdbc).hasOrganizerPlus(UUID.randomUUID()));

        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("package_code IN (?,?)"),
                eq(Boolean.class), any(Object[].class));
    }

    @Test
    void expiredOrRevokedEntitlementsAreNotAcceptedByFeatureGate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class), anyString())).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> new TournamentEntitlementAccess(jdbc).requireOrganizerProForMatch(UUID.randomUUID()));
        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("revoked_at IS NULL"),
                eq(Boolean.class), any(UUID.class), eq("ORGANIZER_PRO"));
    }

    @Test
    void broadcastFeatureRequiresItsOwnAddOn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class), anyString())).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new TournamentEntitlementAccess(jdbc).requireBroadcastAddonForMatch(UUID.randomUUID()));

        assertEquals(403, error.getStatusCode().value());
        assertFalse(error.getReason().isBlank());
    }
}
