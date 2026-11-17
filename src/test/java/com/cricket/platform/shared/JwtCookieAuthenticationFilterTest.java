package com.cricket.platform.shared;

import com.cricket.platform.identity.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtCookieAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesUsingHttpOnlyAccessCookieWhenBearerHeaderIsAbsent() throws Exception {
        JwtService jwt = mock(JwtService.class);
        when(jwt.subject("cookie-token")).thenReturn("player@example.com");
        when(jwt.role("cookie-token")).thenReturn("PLAYER");
        SecurityConfig.JwtFilter filter = new SecurityConfig.JwtFilter(jwt);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtService.ACCESS_COOKIE_NAME, "cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("player@example.com", authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_PLAYER")));
        verify(jwt).subject("cookie-token");
    }

    @Test
    void prefersExplicitBearerTokenForBackwardCompatibility() throws Exception {
        JwtService jwt = mock(JwtService.class);
        when(jwt.subject("bearer-token")).thenReturn("captain@example.com");
        when(jwt.role("bearer-token")).thenReturn("CAPTAIN");
        SecurityConfig.JwtFilter filter = new SecurityConfig.JwtFilter(jwt);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bearer-token");
        request.setCookies(new jakarta.servlet.http.Cookie(JwtService.ACCESS_COOKIE_NAME, "cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("captain@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(jwt).subject("bearer-token");
        verify(jwt, never()).subject("cookie-token");
    }

    @Test
    void doesNotAuthenticateWhenNoSupportedCredentialExists() throws Exception {
        JwtService jwt = mock(JwtService.class);
        SecurityConfig.JwtFilter filter = new SecurityConfig.JwtFilter(jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(jwt);
    }
}
