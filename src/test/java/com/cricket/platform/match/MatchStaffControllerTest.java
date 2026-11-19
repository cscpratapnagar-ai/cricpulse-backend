package com.cricket.platform.match;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;

class MatchStaffControllerTest {
    private final MatchStaffController controller = new MatchStaffController(null);

    @Test
    void normalizesSupportedMatchStaffRoles() {
        assertEquals("SCORER", controller.normalizeRole("scorer"));
        assertEquals("UMPIRE", controller.normalizeRole(" Umpire "));
        assertEquals("COMMENTATOR", controller.normalizeRole("commentator"));
        assertEquals("STREAMER", controller.normalizeRole("streamer"));
    }

    @Test
    void tournamentOwnerCanManageStaffForTournamentFixture() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MatchStaffController controller = new MatchStaffController(jdbc);
        Authentication authentication = mock(Authentication.class);
        UUID matchId = UUID.randomUUID();
        String principal = "organizer@example.com";
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(principal);
        when(authentication.getAuthorities()).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(matchId),
                eq(principal), eq(principal), eq(principal), eq(principal),
                eq(principal), eq(principal))).thenReturn(1);

        assertDoesNotThrow(() -> controller.requireMatchManager(matchId, authentication));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Integer.class), eq(matchId),
                eq(principal), eq(principal), eq(principal), eq(principal),
                eq(principal), eq(principal));
        assertTrue(sql.getValue().contains("JOIN tournaments t ON t.id = tm.tournament_id"));
        assertTrue(sql.getValue().contains("WHERE tm.match_id = m.id"));
    }

    @Test
    void unrelatedUserCannotManageTournamentFixtureStaff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MatchStaffController controller = new MatchStaffController(jdbc);
        Authentication authentication = mock(Authentication.class);
        UUID matchId = UUID.randomUUID();
        String principal = "outsider@example.com";
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(principal);
        when(authentication.getAuthorities()).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(matchId),
                eq(principal), eq(principal), eq(principal), eq(principal),
                eq(principal), eq(principal))).thenReturn(0);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.requireMatchManager(matchId, authentication));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void platformAdminCanManageMatchStaffWithoutTeamOrTournamentOwnership() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MatchStaffController controller = new MatchStaffController(jdbc);
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).when(authentication).getAuthorities();

        assertDoesNotThrow(() -> controller.requireMatchManager(UUID.randomUUID(), authentication));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnauthenticatedMatchStaffManagement() {
        MatchStaffController controller = new MatchStaffController(mock(JdbcTemplate.class));
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.requireMatchManager(UUID.randomUUID(), authentication));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void rejectsUnsupportedMatchStaffRoles() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.normalizeRole("ADMIN"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Unsupported"));
    }
}
