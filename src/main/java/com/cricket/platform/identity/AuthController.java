package com.cricket.platform.identity;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;

import java.time.Duration;
import java.util.Arrays;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final LoginUser loginUser;
    private final JwtService jwtService;
    private final AccessTokenRevocationService tokenRevocations;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final long tokenExpirationSeconds;

    public AuthController(
            LoginUser loginUser,
            JwtService jwtService,
            AccessTokenRevocationService tokenRevocations,
            @Value("${security.auth.cookie-secure:true}") boolean cookieSecure,
            @Value("${security.auth.cookie-same-site:Lax}") String cookieSameSite,
            @Value("${security.jwt.expiration-seconds:86400}") long tokenExpirationSeconds) {
        this.loginUser = loginUser;
        this.jwtService = jwtService;
        this.tokenRevocations = tokenRevocations;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.tokenExpirationSeconds = tokenExpirationSeconds;
    }

    @PostMapping("/login")
    SessionResponse login(@Valid @RequestBody LoginUser.Request request, HttpServletResponse response) {
        LoginUser.AuthResponse auth = loginUser.execute(request);
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie(auth.accessToken(), Duration.ofSeconds(tokenExpirationSeconds)).toString());
        return new SessionResponse(auth.userId(), auth.fullName(), auth.email(), auth.role());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = bearerToken(request);
        if (token == null) token = cookieToken(request);
        if (token != null) {
            try {
                tokenRevocations.revoke(token);
            } catch (JwtException | IllegalArgumentException ignored) {
                // Expired or malformed credentials are already unusable; clear the browser cookie.
            }
        }
        ResponseCookie expiredCookie = accessCookie("", Duration.ZERO);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .build();
    }

    @GetMapping("/csrf")
    java.util.Map<String, String> csrf(CsrfToken token) {
        return java.util.Map.of("token", token.getToken());
    }

    @GetMapping("/ws-ticket")
    java.util.Map<String, String> webSocketTicket(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("PLAYER");
        return java.util.Map.of("token", jwtService.create(authentication.getName(), role, 60));
    }

    @GetMapping("/me")
    SessionResponse me(Authentication authentication) {
        LoginUser.AuthResponse user = loginUser.current(authentication.getName());
        return new SessionResponse(user.userId(), user.fullName(), user.email(), user.role());
    }

    public record SessionResponse(String userId, String fullName, String email, String role) {}

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(cookie -> JwtService.ACCESS_COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    private ResponseCookie accessCookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtService.ACCESS_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api")
                .maxAge(maxAge)
                .build();
    }
}
