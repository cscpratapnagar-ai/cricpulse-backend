package com.cricket.platform.shared;

import com.cricket.platform.identity.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketSecurityInterceptorTest {
    private JwtService jwt;
    private JdbcTemplate jdbc;
    private WebSocketSecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwt = new JwtService("01234567890123456789012345678901", 3600);
        jdbc = mock(JdbcTemplate.class);
        interceptor = new WebSocketSecurityInterceptor(jwt, jdbc);
    }

    private static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectRejectsMissingAuthorization() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void connectRejectsInvalidToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer invalid-token");
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void connectAcceptsValidTokenAndSetsUser() {
        String token = jwt.create("user@example.com", "PLAYER");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer " + token);
        Message<?> result = interceptor.preSend(message(accessor), null);
        assertNotNull(result);
        assertNotNull(StompHeaderAccessor.wrap(result).getUser());
        assertEquals("user@example.com", StompHeaderAccessor.wrap(result).getUser().getName());
    }

    @Test
    void subscribeRejectsUnauthenticatedUser() {
        UUID inningsId = UUID.randomUUID();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/innings/" + inningsId);
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(accessor), null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void subscribeRejectsInvalidDestination() {
        var user = authenticatedUser("user@example.com");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/invalid");
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(accessor), null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void publicSubscriptionDoesNotRequireAuthenticationOrMembershipLookup() {
        UUID inningsId = UUID.randomUUID();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/public/innings/" + inningsId);
        assertDoesNotThrow(() -> interceptor.preSend(message(accessor), null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void publicSubscriptionRejectsMalformedInningsId() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/public/innings/not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(accessor), null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void subscribeRejectsUnauthorizedInnings() {
        UUID inningsId = UUID.randomUUID();
        var user = authenticatedUser("user@example.com");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/innings/" + inningsId);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any())).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void subscribeAcceptsAuthorizedInnings() {
        UUID inningsId = UUID.randomUUID();
        var user = authenticatedUser("user@example.com");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/innings/" + inningsId);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any())).thenReturn(1);
        assertDoesNotThrow(() -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void adminCanSubscribeWithoutMatchMembershipLookup() {
        UUID inningsId = UUID.randomUUID();
        var user = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/innings/" + inningsId);
        assertDoesNotThrow(() -> interceptor.preSend(message(accessor), null));
        verifyNoInteractions(jdbc);
    }

    private static UsernamePasswordAuthenticationToken authenticatedUser(String name) {
        return new UsernamePasswordAuthenticationToken(name, null,
                List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
    }
}
