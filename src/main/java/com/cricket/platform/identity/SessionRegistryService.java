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
        jdbc.update("""
                INSERT INTO user_sessions(session_id, user_id, device_label, user_agent, ip_address, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.fromString(sessionId), userId, deviceLabel(userAgent),
                truncate(userAgent, 512), truncate(ipAddress, 64), Timestamp.from(expiresAt));
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

    public List<SessionView> list(String principal, String currentSessionId) {
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
        int changed = jdbc.update("""
                UPDATE user_sessions s SET revoked_at = now()
                FROM users u
                WHERE s.user_id = u.id AND s.session_id = ?
                  AND (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL
                """, UUID.fromString(sessionId), principal, principal);
        if (changed > 0) activeSockets.closeByUserSession(sessionId);
        return changed > 0;
    }

    @Transactional
    public int revokeAll(String principal) {
        List<UUID> sessions = jdbc.query("""
                SELECT s.session_id FROM user_sessions s JOIN users u ON u.id = s.user_id
                WHERE (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL
                """, (rs, row) -> rs.getObject(1, UUID.class), principal, principal);
        int changed = jdbc.update("""
                UPDATE user_sessions s SET revoked_at = now()
                FROM users u
                WHERE s.user_id = u.id AND (LOWER(u.email) = LOWER(?) OR CAST(u.id AS TEXT) = ?)
                  AND s.revoked_at IS NULL
                """, principal, principal);
        sessions.forEach(id -> activeSockets.closeByUserSession(id.toString()));
        return changed;
    }

    @Transactional
    public void revokeBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        int changed = jdbc.update("""
                UPDATE user_sessions SET revoked_at = now()
                WHERE session_id = ? AND revoked_at IS NULL
                """, UUID.fromString(sessionId));
        if (changed > 0) activeSockets.closeByUserSession(sessionId);
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

    public record SessionView(String sessionId, String device, String userAgent, String ipAddress,
                              Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current) {}
}
