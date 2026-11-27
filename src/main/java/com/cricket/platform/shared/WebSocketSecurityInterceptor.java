package com.cricket.platform.shared;

import com.cricket.platform.identity.ActiveWebSocketSessions;
import com.cricket.platform.identity.JwtService;
import com.cricket.platform.identity.SessionRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class WebSocketSecurityInterceptor implements ChannelInterceptor {
    private static final String PRIVATE_TOPIC_PREFIX = "/topic/innings/";
    private static final String PUBLIC_TOPIC_PREFIX = "/topic/public/innings/";

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final SessionRegistryService sessions;
    private final ActiveWebSocketSessions activeSockets;
    private final boolean relayEnabled;

    @Autowired
    public WebSocketSecurityInterceptor(JwtService jwtService, JdbcTemplate jdbc,
            SessionRegistryService sessions, ActiveWebSocketSessions activeSockets,
            @org.springframework.beans.factory.annotation.Value("${app.websocket.broker-relay.enabled:false}") boolean relayEnabled) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.activeSockets = activeSockets;
        this.relayEnabled = relayEnabled;
    }

    WebSocketSecurityInterceptor(JwtService jwtService, JdbcTemplate jdbc) {
        this(jwtService, jdbc, false);
    }

    WebSocketSecurityInterceptor(JwtService jwtService, JdbcTemplate jdbc, boolean relayEnabled) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.sessions = null;
        this.activeSockets = null;
        this.relayEnabled = relayEnabled;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            Authentication authentication = authenticate(accessor.getFirstNativeHeader("Authorization"), accessor.getSessionId());
            if (authentication == null) return message;
            accessor.setUser(authentication);
            return message;
        }

        if (StompCommand.DISCONNECT.equals(accessor.getCommand())) return message;

        Authentication authentication = asAuthentication(accessor.getUser());
        if (authentication == null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authentication = authenticate(accessor.getFirstNativeHeader("Authorization"), accessor.getSessionId());
        }
        if (authentication != null && sessions != null) {
            Object details = authentication.getDetails();
            if (details == null || !sessions.isActive(details.toString(), authentication.getName())) {
                throw new IllegalArgumentException("WebSocket device session has been revoked or expired");
            }
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            UUID inningsId = inningsId(destination);
            if (inningsId == null) throw new IllegalArgumentException("Invalid live match subscription destination");
            if (destination.startsWith(PUBLIC_TOPIC_PREFIX)) {
                rewriteRabbitTopicDestination(accessor, inningsId, true);
                return message;
            }

            if (authentication == null || !authentication.isAuthenticated()) {
                throw new IllegalArgumentException("WebSocket authentication is required");
            }
            if (!canViewInnings(authentication, inningsId)) {
                throw new IllegalArgumentException("You are not authorized to subscribe to this live match");
            }
            rewriteRabbitTopicDestination(accessor, inningsId, false);
        }
        return message;
    }

    private Authentication authenticate(String header, String socketId) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        try {
            String token = header.substring(7).trim();
            String subject = jwtService.subject(token);
            String role = jwtService.role(token).toUpperCase();
            String sessionId = jwtService.sessionId(token);
            if (sessions != null && (sessionId == null || !sessions.isActive(sessionId, subject))) return null;
            var authentication = new UsernamePasswordAuthenticationToken(subject, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            if (sessionId != null) authentication.setDetails(sessionId);
            if (activeSockets != null) {
                activeSockets.bind(sessionId, socketId);
                // Close the CONNECT/revoke race: revocation may commit between the first
                // active-session check and local socket registration.
                if (sessions != null && !sessions.isActive(sessionId, subject)) {
                    activeSockets.closeByUserSession(sessionId);
                    return null;
                }
            }
            return authentication;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Authentication asAuthentication(java.security.Principal principal) {
        return principal instanceof Authentication authentication ? authentication : null;
    }

    private UUID inningsId(String destination) {
        String prefix = destination != null && destination.startsWith(PUBLIC_TOPIC_PREFIX)
                ? PUBLIC_TOPIC_PREFIX : PRIVATE_TOPIC_PREFIX;
        if (destination == null || !destination.startsWith(prefix)) return null;
        try {
            String value = destination.substring(prefix.length());
            if (value.isBlank() || value.contains("/")) return null;
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void rewriteRabbitTopicDestination(StompHeaderAccessor accessor, UUID inningsId, boolean isPublic) {
        if (!relayEnabled) return;
        accessor.setDestination(isPublic
                ? "/topic/public.innings." + inningsId
                : "/topic/innings." + inningsId);
    }

    private boolean canViewInnings(Authentication authentication, UUID inningsId) {
        if (hasRole(authentication, "ROLE_ADMIN") || hasRole(authentication, "ROLE_SCORER")) return true;
        String principal = authentication.getName();
        Integer allowed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM innings i JOIN matches m ON m.id = i.match_id
                WHERE i.id = ? AND (
                  EXISTS (SELECT 1 FROM teams t JOIN users u ON u.id = t.owner_id
                    WHERE t.id IN (m.team_a_id, m.team_b_id)
                    AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?))
                  OR EXISTS (SELECT 1 FROM team_members tm JOIN players p ON p.id = tm.player_id
                    JOIN users u ON u.id = p.user_id WHERE tm.team_id IN (m.team_a_id, m.team_b_id)
                    AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?))
                )
                """, Integer.class, inningsId, principal, principal, principal, principal);
        return allowed != null && allowed > 0;
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).anyMatch(role::equals);
    }
}
