package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthenticationPasswordFlowTest {

    @Test
    void bcryptRoundTripMustWork() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "Test@12345";
        String hash = encoder.encode(password);

        assertNotNull(hash);
        assertTrue(hash.startsWith("$2"));
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches("Wrong@12345", hash));
    }

    @Test
    void registerHashMustBeAcceptedByLogin() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JwtService jwt = mock(JwtService.class);

        UUID userId = UUID.randomUUID();
        String email = "test-auth@example.com";
        String password = "Test@12345";

        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(email)))
                .thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String storedHash = invocation.getArgument(5);
                    when(jdbc.queryForMap(anyString(), eq(email)))
                            .thenReturn(Map.of(
                                    "id", userId,
                                    "full_name", "Auth Test",
                                    "email", email,
                                    "role", "PLAYER",
                                    "password_hash", storedHash));
                    return 1;
                });
        when(jwt.create(email, "PLAYER")).thenReturn("test-token");

        RegisterUser register = new RegisterUser(jdbc, encoder);
        register.execute(new RegisterUser.Request(
                "Auth Test", email, "9876543210", "PLAYER", password));

        LoginUser login = new LoginUser(jdbc, encoder, jwt);
        LoginUser.AuthResponse response = login.execute(
                new LoginUser.Request(email, password));

        assertEquals("test-token", response.accessToken());
        assertEquals(userId.toString(), response.userId());
        assertEquals(email, response.email());
        assertEquals("PLAYER", response.role());
    }
}
