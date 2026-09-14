package com.cricket.platform.shared;

import com.cricket.platform.identity.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

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

    @Test
    void connectRejectsMissingAuthorization() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null));
    }

    @Test
    void connectRejectsInvalidToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer invalid-token");
        accessor.setLeaveMutable(true);
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null));
    }

    @Test
    void connectAcceptsValidTokenAndSetsUser() {
        String token = jwt.create("user@example.com", "PLAYER");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        var result = interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
        assertNotNull(result);
        assertNotNull(StompHeaderAccessor.wrap(result).getUser());
        assertEquals("user@example.com", StompHeaderAccessor.wrap(result).getUser().getName());
    }

    @Test
    void subscribeRejectsUnauthenticatedUser() {
        UUID inningsId = UUID.randomUUID();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/innings/" + inningsId);
        accessor.setLeaveMutable(true);
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void subscribeRejectsInvalidDestination() {
        String token = jwt.create("user@example.com", "PLAYER");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("user@example.com", null));
        accessor.setDestination("/topic/invalid");
        accessor.setLeaveMutable(true);
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null));
        verifyNoInteractions(jdbc);
        assertNotNull(token);
    }

    @Test
    void subscribeRejectsUnauthorizedInnings() {
        UUID inningsId = UUID.randomUUID();
        var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("user@example.com", null);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/innings/" + inningsId);
        accessor.setLeaveMutable(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any())).thenReturn(0);
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null));
    }

    @Test
    void subscribeAcceptsAuthorizedInnings() {
        UUID inningsId = UUID.randomUUID();
        var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("user@example.com", null);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/innings/" + inningsId);
        accessor.setLeaveMutable(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any())).thenReturn(1);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertSame(message, interceptor.preSend(message, null));
    }

    @Test
    void adminCanSubscribeWithoutMatchMembershipLookup() {
        UUID inningsId = UUID.randomUUID();
        var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin@example.com", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination("/topic/innings/" + inningsId);
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertSame(message, interceptor.preSend(message, null));
        verifyNoInteractions(jdbc);
    }
}
