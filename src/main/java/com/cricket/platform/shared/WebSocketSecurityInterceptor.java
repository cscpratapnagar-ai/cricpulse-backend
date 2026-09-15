package com.cricket.platform.shared;

import com.cricket.platform.identity.JwtService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
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

    public WebSocketSecurityInterceptor(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            Authentication authentication = authenticate(accessor.getFirstNativeHeader("Authorization"));
            if (authentication == null) {
                throw new IllegalArgumentException("WebSocket authentication is required");
            }
            accessor.setUser(authentication);
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            UUID inningsId = inningsId(destination);
            if (inningsId == null) {
                throw new IllegalArgumentException("Invalid live match subscription destination");
            }

            if (destination.startsWith(PUBLIC_TOPIC_PREFIX)) {
                return message;
            }

            Authentication authentication = asAuthentication(accessor.getUser());
            if (authentication == null || !authentication.isAuthenticated()) {
                throw new IllegalArgumentException("WebSocket authentication is required");
            }

            if (!canViewInnings(authentication, inningsId)) {
                throw new IllegalArgumentException("You are not authorized to subscribe to this live match");
            }
        }

        return message;
    }

    private Authentication authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = header.substring(7).trim();
            String subject = jwtService.subject(token);
            String role = jwtService.role(token).toUpperCase();
            return new UsernamePasswordAuthenticationToken(
                    subject,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Authentication asAuthentication(java.security.Principal principal) {
        return principal instanceof Authentication authentication ? authentication : null;
    }

    private UUID inningsId(String destination) {
        String prefix = destination != null && destination.startsWith(PUBLIC_TOPIC_PREFIX)
                ? PUBLIC_TOPIC_PREFIX
                : PRIVATE_TOPIC_PREFIX;
        if (destination == null || !destination.startsWith(prefix)) {
            return null;
        }
        try {
            String value = destination.substring(prefix.length());
            if (value.isBlank() || value.contains("/")) {
                return null;
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean canViewInnings(Authentication authentication, UUID inningsId) {
        if (hasRole(authentication, "ROLE_ADMIN") || hasRole(authentication, "ROLE_SCORER")) {
            return true;
        }

        String principal = authentication.getName();
        Integer allowed = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM innings i
                JOIN matches m ON m.id = i.match_id
                WHERE i.id = ?
                  AND (
                    EXISTS (
                      SELECT 1 FROM teams t
                      JOIN users u ON u.id = t.owner_id
                      WHERE t.id IN (m.team_a_id, m.team_b_id)
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                    OR EXISTS (
                      SELECT 1 FROM team_members tm
                      JOIN players p ON p.id = tm.player_id
                      JOIN users u ON u.id = p.user_id
                      WHERE tm.team_id IN (m.team_a_id, m.team_b_id)
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                  )
                """, Integer.class, inningsId, principal, principal, principal, principal);

        return allowed != null && allowed > 0;
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(role::equals);
    }
}
