package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoringAccessTest {
    @Test
    void assignedScorerCanAccessMatchAndControlScoring() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScoringAccess access = new ScoringAccess(jdbc);
        Authentication scorer = new UsernamePasswordAuthenticationToken(
                "scorer@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UUID matchId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        access.requireMatchAccess(matchId, scorer);
        access.requireMatchManager(matchId, scorer);

        verify(jdbc, org.mockito.Mockito.times(2)).queryForObject(
                contains("ms.role = 'SCORER'"), eq(Integer.class), any(Object[].class));
    }
}
