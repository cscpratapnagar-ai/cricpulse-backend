package com.cricket.platform.shared;

import com.cricket.platform.identity.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {
    private final List<String> allowedOriginPatterns;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public SecurityConfig(
            @Value("${app.cors.allowed-origin-patterns:http://localhost:4200,https://*.app.github.dev,https://*.cricketpulse.app}") String allowedOriginPatterns,
            @Value("${security.auth.cookie-secure:true}") boolean cookieSecure,
            @Value("${security.auth.cookie-same-site:Lax}") String cookieSameSite) {
        this.allowedOriginPatterns = List.of(allowedOriginPatterns.split(","));
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Command-Id", "X-Scorer-Lease", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/api");
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .secure(cookieSecure)
                .sameSite(cookieSameSite));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/csrf", "/api/users", "/api/public/**", "/ws/**", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/scoring/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/matches/**").hasAnyRole("ADMIN", "CAPTAIN", "PLAYER")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class JwtFilter extends OncePerRequestFilter {
        private final JwtService jwt;
        JwtFilter(JwtService jwt) { this.jwt = jwt; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String token = bearerToken(request);
            if (token == null) token = cookieToken(request);
            if (token != null) {
                try {
                    String subject = jwt.subject(token);
                    String role = jwt.role(token).toUpperCase();
                    var authentication = new UsernamePasswordAuthenticationToken(
                            subject, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (RuntimeException ignored) {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        }

        private String bearerToken(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
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
    }
}
