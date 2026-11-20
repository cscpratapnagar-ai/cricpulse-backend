package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScorerLeaseServiceTest {

    @Test
    void holderCheckUsesRowLockAndAcceptsOnlyMatchingUnexpiredLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        when(jdbc.query(anyString(), any(RowMapper.class), eq(inningsId), eq("scorer-a"), eq(token)))
                .thenReturn(List.of(token));

        service.requireHolder(inningsId, "scorer-a", token);

        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("expires_at > clock_timestamp()") && sql.contains("FOR UPDATE")),
                any(RowMapper.class), eq(inningsId), eq("scorer-a"), eq(token));
    }

    @Test
    void holderCheckRejectsMissingExpiredOrForeignLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        when(jdbc.query(anyString(), any(RowMapper.class), eq(inningsId), eq("scorer-b"), eq(token)))
                .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requireHolder(inningsId, "scorer-b", token));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void renewRejectsLeaseThatHasExpiredOrBeenTakenOver() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        when(jdbc.update(anyString(), eq(20), eq(inningsId), eq("scorer-a"), eq(token))).thenReturn(0);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.renew(inningsId, "scorer-a", token));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void releaseIsScopedToInningsHolderAndToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        service.release(inningsId, "scorer-a", token);

        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("DELETE FROM scorer_leases")
                        && sql.contains("innings_id = ? AND holder_key = ? AND lease_token = ?")),
                eq(inningsId), eq("scorer-a"), eq(token));
    }

    @Test
    void acquireRejectsBlankHolderOrMissingTokenBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);

        assertThrows(ResponseStatusException.class, () -> service.acquire(UUID.randomUUID(), " ", UUID.randomUUID()));
        assertThrows(ResponseStatusException.class, () -> service.acquire(UUID.randomUUID(), "scorer-a", null));
    }

    @Test
    void acquireRejectsTakeoverWhileAnotherScorerLeaseIsStillActive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID requestedToken = UUID.randomUUID();
        UUID currentToken = UUID.randomUUID();

        when(jdbc.update(anyString(), eq(inningsId), eq("scorer-b"), eq(requestedToken), eq(20)))
                .thenReturn(0);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(inningsId)))
                .thenReturn(new ScorerLeaseService.LeaseState(
                        inningsId, "scorer-a", currentToken, java.time.OffsetDateTime.now().plusSeconds(10)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.acquire(inningsId, "scorer-b", requestedToken));

        assertEquals(409, exception.getStatusCode().value());
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("WHERE scorer_leases.expires_at <= clock_timestamp()")
                        && sql.contains("scorer_leases.holder_key = EXCLUDED.holder_key")
                        && sql.contains("scorer_leases.lease_token = EXCLUDED.lease_token")),
                eq(inningsId), eq("scorer-b"), eq(requestedToken), eq(20));
    }

    @Test
    void acquireRenewsOnlyTheSameHolderAndTokenWithoutResettingAcquiredAt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        var lease = new ScorerLeaseService.LeaseState(
                inningsId, "scorer-a", token, java.time.OffsetDateTime.now().plusSeconds(20));

        when(jdbc.update(anyString(), eq(inningsId), eq("scorer-a"), eq(token), eq(20))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(inningsId))).thenReturn(lease);

        ScorerLeaseService.LeaseState result = service.acquire(inningsId, "scorer-a", token);

        assertEquals(lease, result);
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("WHEN scorer_leases.holder_key = EXCLUDED.holder_key")
                        && sql.contains("AND scorer_leases.lease_token = EXCLUDED.lease_token")
                        && sql.contains("THEN scorer_leases.acquired_at ELSE clock_timestamp() END")),
                eq(inningsId), eq("scorer-a"), eq(token), eq(20));
    }

    @Test
    void renewExtendsOnlyAnUnexpiredLeaseOwnedByTheSameScorerAndToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScorerLeaseService service = new ScorerLeaseService(jdbc);
        UUID inningsId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        var lease = new ScorerLeaseService.LeaseState(
                inningsId, "scorer-a", token, java.time.OffsetDateTime.now().plusSeconds(20));

        when(jdbc.update(anyString(), eq(20), eq(inningsId), eq("scorer-a"), eq(token))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(inningsId))).thenReturn(lease);

        assertEquals(lease, service.renew(inningsId, "scorer-a", token));
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("holder_key = ? AND lease_token = ? AND expires_at > clock_timestamp()")),
                eq(20), eq(inningsId), eq("scorer-a"), eq(token));
    }
}
