package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;

class TournamentStaffControllerTest {
    @Test
    void delegatedManagerCanAccessTournamentWorkspace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TournamentStaffController controller = new TournamentStaffController(jdbc);
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        String principal = "manager@example.com";
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(principal);
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(principal), eq(principal)))
                .thenReturn(userId);
        when(jdbc.queryForObject(contains("owner_id"), eq(Integer.class), eq(tournamentId), eq(userId)))
                .thenReturn(0);
        when(jdbc.queryForObject(contains("FROM tournament_staff"), eq(Integer.class), eq(tournamentId), eq(userId)))
                .thenReturn(1);

        org.junit.jupiter.api.Assertions.assertFalse(
                controller.requireTournamentAccess(tournamentId, authentication));
    }

    @Test
    void unrelatedUserCannotAccessTournamentWorkspace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TournamentStaffController controller = new TournamentStaffController(jdbc);
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        String principal = "outsider@example.com";
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(principal);
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(principal), eq(principal)))
                .thenReturn(userId);
        when(jdbc.queryForObject(contains("owner_id"), eq(Integer.class), eq(tournamentId), eq(userId)))
                .thenReturn(0);
        when(jdbc.queryForObject(contains("FROM tournament_staff"), eq(Integer.class), eq(tournamentId), eq(userId)))
                .thenReturn(0);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.requireTournamentAccess(tournamentId, authentication));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void onlyTournamentOwnerCanGrantManagerAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TournamentStaffController controller = new TournamentStaffController(jdbc);
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        String principal = "manager@example.com";
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(principal);
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(principal), eq(principal)))
                .thenReturn(userId);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tournamentId), eq(userId)))
                .thenReturn(0);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.requireTournamentOwner(tournamentId, authentication));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }
}
