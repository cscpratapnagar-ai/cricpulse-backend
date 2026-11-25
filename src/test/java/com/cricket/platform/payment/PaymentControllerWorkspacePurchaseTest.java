package com.cricket.platform.payment;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentControllerWorkspacePurchaseTest {
    @Test
    void clubAndAcademyPackagesRequireAWorkspace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Authentication authentication = authenticatedUser();
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(UUID.class),
                eq("manager@example.com"), eq("manager@example.com"))).thenReturn(userId);

        PaymentController controller = new PaymentController(jdbc, null, "");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.createOrder(new PaymentController.CreateOrderRequest(
                        "CLUB_ACADEMY_PLUS", "academy-order-1", null, null), authentication));

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void onlyWorkspaceOwnersOrManagersCanRequestAcademyPackages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Authentication authentication = authenticatedUser();
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(UUID.class),
                eq("manager@example.com"), eq("manager@example.com"))).thenReturn(userId);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM teams"), eq(Integer.class),
                eq(teamId), eq(userId), eq(userId))).thenReturn(0);

        PaymentController controller = new PaymentController(jdbc, null, "");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.createOrder(new PaymentController.CreateOrderRequest(
                        "ACADEMY_BRANDING", "academy-order-2", null, teamId), authentication));

        assertEquals(403, error.getStatusCode().value());
    }

    private Authentication authenticatedUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("manager@example.com");
        return authentication;
    }
}
