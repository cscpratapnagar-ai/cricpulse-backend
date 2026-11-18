package com.cricket.platform.scoring;

import java.time.OffsetDateTime;
import java.util.List;
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
                VALUES (?, ?, ?, clock_timestamp(), clock_timestamp() + (? * interval '1 second'), clock_timestamp())
                ON CONFLICT (innings_id) DO UPDATE SET
                    holder_key = EXCLUDED.holder_key,
                    lease_token = EXCLUDED.lease_token,
                    acquired_at = CASE
                        WHEN scorer_leases.holder_key = EXCLUDED.holder_key
                         AND scorer_leases.lease_token = EXCLUDED.lease_token
                        THEN scorer_leases.acquired_at ELSE clock_timestamp() END,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = clock_timestamp()
                WHERE scorer_leases.expires_at <= clock_timestamp()
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
                SET expires_at = clock_timestamp() + (? * interval '1 second'), updated_at = clock_timestamp()
                WHERE innings_id = ? AND holder_key = ? AND lease_token = ? AND expires_at > clock_timestamp()
                """, LEASE_SECONDS, inningsId, holderKey, token);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scorer lease expired or belongs to another device. Reacquire the scoring session.");
        }
        return current(inningsId);
    }

    @Transactional
    public void requireHolder(UUID inningsId, String holderKey, UUID token) {
        // Lock the lease row until the scoring transaction commits. A takeover
        // cannot race between lease validation and delivery persistence.
        List<UUID> held = jdbc.query("""
                SELECT lease_token FROM scorer_leases
                WHERE innings_id = ? AND holder_key = ? AND lease_token = ? AND expires_at > clock_timestamp()
                FOR UPDATE
                """, (rs, rowNum) -> rs.getObject("lease_token", UUID.class),
                inningsId, holderKey, token);
        if (held.isEmpty()) {
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
