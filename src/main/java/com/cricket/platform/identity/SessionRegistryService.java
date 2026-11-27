package com.cricket.platform.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SessionRegistryService {
    private final JdbcTemplate jdbc;
    private final ActiveWebSocketSessions activeSockets;

    public SessionRegistryService(JdbcTemplate jdbc, ActiveWebSocketSessions activeSockets) {
        this.jdbc = jdbc;
        this.activeSockets = activeSockets;
    }

    @Transactional
    public void create(UUID userId, String sessionId, Instant expiresAt, String userAgent, String ipAddress) {
        cleanupExpired();
        jdbc.update("""
                INSERT INTO user_sessions(session_id, user_id, device_label, user_agent, ip_address, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.fromString(sessionId), userId, deviceLabel(userAgent),
                truncate(userAgent, 512), truncate(ipAddress, 64), Timestamp.from(expiresAt));
        audit(userId, sessionId, "LOGIN", ipAddress, userAgent);
    }

    public boolean isActive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_sessions
                WHERE session_id = ? AND revoked_at IS NULL AND expires_at > now())
                """, Boolean.class, UUID.fromString(sessionId)));
    }

    public boolean isActive(String sessionId, String principal) {
        if (sessionId == null || sessionId.isBlank() || principal == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM user_sessions s JOIN users u ON u.id = s.user_id
                  WHERE s.session_id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                    AND (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                )
                """, Boolean.class, UUID.fromString(sessionId), principal, principal));
    }

    /** Refresh activity at most once every five minutes to avoid a write per API request. */
    @Transactional
    public void touch(String sessionId, String principal) {
        if (sessionId == null || sessionId.isBlank() || principal == null || principal.isBlank()) return;
        jdbc.update("""
                UPDATE user_sessions s SET last_seen_at = now()
                FROM users u
                WHERE s.user_id = u.id AND s.session_id = ?
                  AND (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL AND s.expires_at > now()
                  AND s.last_seen_at < now() - INTERVAL '5 minutes'
                """, UUID.fromString(sessionId), principal, principal);
    }

    public List<SessionView> list(String principal, String currentSessionId) {
        cleanupExpired();
        return jdbc.query("""
                SELECT s.session_id, s.device_label, s.user_agent, s.ip_address,
                       s.created_at, s.last_seen_at, s.expires_at
                FROM user_sessions s JOIN users u ON u.id = s.user_id
                WHERE (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL AND s.expires_at > now()
                ORDER BY s.last_seen_at DESC, s.created_at DESC
                """, (rs, row) -> new SessionView(
                rs.getObject("session_id", UUID.class).toString(),
                rs.getString("device_label"), rs.getString("user_agent"), rs.getString("ip_address"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("session_id", UUID.class).toString().equals(currentSessionId)),
                principal, principal);
    }

    @Transactional
    public boolean revoke(String principal, String sessionId) {
        UUID id = UUID.fromString(sessionId);
        UUID userId = jdbc.query("""
                SELECT s.user_id FROM user_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.session_id = ? AND (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, id, principal, principal);
        if (userId == null) return false;
        int changed = jdbc.update("""
                UPDATE user_sessions SET revoked_at = now()
                WHERE session_id = ? AND revoked_at IS NULL
                """, id);
        if (changed > 0) {
            audit(userId, sessionId, "SESSION_REVOKED", null, null);
            activeSockets.closeByUserSession(sessionId);
        }
        return changed > 0;
    }

    @Transactional
    public int revokeAll(String principal) {
        List<SessionAuditTarget> targets = jdbc.query("""
                SELECT s.session_id, s.user_id, s.ip_address, s.user_agent
                FROM user_sessions s JOIN users u ON u.id = s.user_id
                WHERE (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL
                """, (rs, row) -> new SessionAuditTarget(
                rs.getObject("session_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("ip_address"), rs.getString("user_agent")), principal, principal);
        int changed = jdbc.update("""
                UPDATE user_sessions s SET revoked_at = now()
                FROM users u
                WHERE s.user_id = u.id AND (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL
                """, principal, principal);
        if (changed > 0) {
            targets.forEach(target -> {
                audit(target.userId(), target.sessionId().toString(), "LOGOUT_ALL",
                        target.ipAddress(), target.userAgent());
                activeSockets.closeByUserSession(target.sessionId().toString());
            });
        }
        return changed;
    }

    @Transactional
    public void revokeBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        UUID id = UUID.fromString(sessionId);
        SessionAuditTarget target = jdbc.query("""
                SELECT user_id, ip_address, user_agent FROM user_sessions
                WHERE session_id = ?
                """, rs -> rs.next() ? new SessionAuditTarget(id, rs.getObject("user_id", UUID.class),
                rs.getString("ip_address"), rs.getString("user_agent")) : null, id);
        int changed = jdbc.update("""
                UPDATE user_sessions SET revoked_at = now()
                WHERE session_id = ? AND revoked_at IS NULL
                """, id);
        if (changed > 0) {
            if (target != null) audit(target.userId(), sessionId, "LOGOUT", target.ipAddress(), target.userAgent());
            activeSockets.closeByUserSession(sessionId);
        }
    }

    /** Opportunistic cleanup on login and session-list requests; no scheduler dependency. */
    @Transactional
    public int cleanupExpired() {
        return jdbc.update("""
                DELETE FROM user_sessions
                WHERE expires_at <= now()
                   OR revoked_at < now() - INTERVAL '30 days'
                """);
    }

    private void audit(UUID userId, String sessionId, String eventType, String ipAddress, String userAgent) {
        jdbc.update("""
                INSERT INTO user_session_audit(user_id, session_id, event_type, ip_address, user_agent)
                VALUES (?, ?, ?, ?, ?)
                """, userId, sessionId == null ? null : UUID.fromString(sessionId),
                eventType, truncate(ipAddress, 64), truncate(userAgent, 512));
    }

    private String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown device";
        String value = userAgent.toLowerCase();
        String browser = value.contains("edg/") ? "Edge" : value.contains("chrome/") ? "Chrome"
                : value.contains("firefox/") ? "Firefox" : value.contains("safari/") ? "Safari" : "Browser";
        String platform = value.contains("android") ? "Android" : value.contains("iphone") || value.contains("ipad")
                ? "iOS" : value.contains("windows") ? "Windows" : value.contains("mac os") ? "macOS"
                : value.contains("linux") ? "Linux" : "Device";
        return platform + " · " + browser;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record SessionAuditTarget(UUID sessionId, UUID userId, String ipAddress, String userAgent) {}

    public record SessionView(String sessionId, String device, String userAgent, String ipAddress,
                              Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current) {}
}
