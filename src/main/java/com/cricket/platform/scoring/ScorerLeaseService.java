package com.cricket.platform.scoring;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ScorerLeaseService {
    private static final int LEASE_SECONDS = 20;
    private final JdbcTemplate jdbc;

    public ScorerLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public LeaseState acquire(UUID inningsId, String holderKey, UUID token) {
        if (holderKey == null || holderKey.isBlank() || token == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A scorer lease token is required.");
        }
        jdbc.update("""
                INSERT INTO scorer_leases(innings_id, holder_key, lease_token, acquired_at, expires_at, updated_at)
                VALUES (?, ?, ?, now(), now() + (? * interval '1 second'), now())
                ON CONFLICT (innings_id) DO UPDATE SET
                    holder_key = EXCLUDED.holder_key,
                    lease_token = EXCLUDED.lease_token,
                    acquired_at = CASE
                        WHEN scorer_leases.holder_key = EXCLUDED.holder_key
                         AND scorer_leases.lease_token = EXCLUDED.lease_token
                        THEN scorer_leases.acquired_at ELSE now() END,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = now()
                WHERE scorer_leases.expires_at <= now()
                   OR (scorer_leases.holder_key = EXCLUDED.holder_key
                       AND scorer_leases.lease_token = EXCLUDED.lease_token)
                """, inningsId, holderKey, token, LEASE_SECONDS);

        LeaseState state = current(inningsId);
        if (!holderKey.equals(state.holderKey()) || !token.equals(state.leaseToken())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another scorer currently holds this innings. Take over after the lease expires.");
        }
        return state;
    }

    @Transactional
    public LeaseState renew(UUID inningsId, String holderKey, UUID token) {
        int updated = jdbc.update("""
                UPDATE scorer_leases
                SET expires_at = now() + (? * interval '1 second'), updated_at = now()
                WHERE innings_id = ? AND holder_key = ? AND lease_token = ? AND expires_at > now()
                """, LEASE_SECONDS, inningsId, holderKey, token);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scorer lease expired or belongs to another device. Reacquire the scoring session.");
        }
        return current(inningsId);
    }

    @Transactional
    public void requireHolder(UUID inningsId, String holderKey, UUID token) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scorer_leases
                WHERE innings_id = ? AND holder_key = ? AND lease_token = ? AND expires_at > now()
                """, Integer.class, inningsId, holderKey, token);
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scoring lease is missing or expired. Refresh the scoring session before recording a delivery.");
        }
    }

    @Transactional
    public void release(UUID inningsId, String holderKey, UUID token) {
        jdbc.update("""
                DELETE FROM scorer_leases
                WHERE innings_id = ? AND holder_key = ? AND lease_token = ?
                """, inningsId, holderKey, token);
    }

    private LeaseState current(UUID inningsId) {
        return jdbc.queryForObject("""
                SELECT innings_id, holder_key, lease_token, expires_at
                FROM scorer_leases WHERE innings_id = ?
                """, (rs, rowNum) -> new LeaseState(
                rs.getObject("innings_id", UUID.class),
                rs.getString("holder_key"),
                rs.getObject("lease_token", UUID.class),
                rs.getObject("expires_at", OffsetDateTime.class)), inningsId);
    }

    public record LeaseState(UUID inningsId, String holderKey, UUID leaseToken, OffsetDateTime expiresAt) {}
}
