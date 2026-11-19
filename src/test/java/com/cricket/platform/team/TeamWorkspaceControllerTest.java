package com.cricket.platform.team;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TeamWorkspaceControllerTest {
    @Test
    void returnsOwnedAndMemberWorkspacesWithContextRole() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TeamController controller = new TeamController(mock(CreateTeam.class), mock(GetTeam.class), jdbc);
        Authentication user = new UsernamePasswordAuthenticationToken(
                "member@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
        TeamController.WorkspaceView workspace = new TeamController.WorkspaceView(
                UUID.randomUUID(), "Weekend Warriors", "Surat", UUID.randomUUID(), "PLAYER", false);
        doReturn(List.of(workspace)).when(jdbc).query(
                anyString(), any(RowMapper.class), any(Object[].class));

        List<TeamController.WorkspaceView> result = controller.workspaces(user);

        assertEquals(List.of(workspace), result);
        verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("member_user"),
                any(RowMapper.class),
                eq("member@example.com"), eq("member@example.com"),
                eq("member@example.com"), eq("member@example.com"),
                eq("member@example.com"), eq("member@example.com"),
                eq("member@example.com"), eq("member@example.com"));
    }

    @Test
    void rejectsUnauthenticatedWorkspaceRequests() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TeamController controller = new TeamController(mock(CreateTeam.class), mock(GetTeam.class), jdbc);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.workspaces(null));

        assertEquals(401, exception.getStatusCode().value());
    }
}
