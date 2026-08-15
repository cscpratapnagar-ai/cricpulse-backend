package com.cricket.platform.shared;

import com.cricket.platform.identity.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:4200",
                "https://*.app.github.dev",
                "https://*.cricketpulse.app"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/users", "/ws/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Scoring uses match/team membership authorization in ScoringAccess.
                        // This is required because a user can be globally PLAYER while being
                        // OWNER/MANAGER/CAPTAIN of a specific team.
                        .requestMatchers("/api/scoring/**").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/matches/**").hasAnyRole("ADMIN", "CAPTAIN", "PLAYER")
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
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    String token = header.substring(7);
                    String subject = jwt.subject(token);
                    String role = jwt.role(token).toUpperCase();
                    var authentication = new UsernamePasswordAuthenticationToken(
                            subject, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .setAuthentication(authentication);
                } catch (RuntimeException ignored) {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        }
    }
}
