package com.cricket.platform.identity;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final LoginUser loginUser;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final long tokenExpirationSeconds;

    public AuthController(
            LoginUser loginUser,
            @Value("${security.auth.cookie-secure:true}") boolean cookieSecure,
            @Value("${security.auth.cookie-same-site:Lax}") String cookieSameSite,
            @Value("${security.jwt.expiration-seconds:86400}") long tokenExpirationSeconds) {
        this.loginUser = loginUser;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.tokenExpirationSeconds = tokenExpirationSeconds;
    }

    @PostMapping("/login")
    LoginUser.AuthResponse login(@Valid @RequestBody LoginUser.Request request, HttpServletResponse response) {
        LoginUser.AuthResponse auth = loginUser.execute(request);
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie(auth.accessToken(), Duration.ofSeconds(tokenExpirationSeconds)).toString());
        return auth;
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        ResponseCookie expiredCookie = accessCookie("", Duration.ZERO);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .build();
    }

    @GetMapping("/me")
    LoginUser.AuthResponse me(Authentication authentication) {
        return loginUser.current(authentication.getName());
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
