package com.cricket.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private static final Set<String> ASSIGNABLE_ROLES =
            Set.of("ADMIN", "PLAYER", "SCORER", "UMPIRE", "COMMENTATOR", "STREAMER");

    private final JdbcTemplate jdbc;

    public AdminUserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<AdminUserView> list(@RequestParam(defaultValue = "") String q,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination parameters.");
        }
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + term + "%";
        return jdbc.query("""
                SELECT id, full_name, email, phone, role, created_at
                FROM users
                WHERE LOWER(COALESCE(full_name, '')) LIKE ?
                   OR LOWER(COALESCE(email, '')) LIKE ?
                ORDER BY created_at DESC, full_name ASC
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new AdminUserView(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                pattern, pattern, size, page * size);
    }

    @PatchMapping("/{userId}/role")
    @Transactional
    AdminUserView updateRole(@PathVariable UUID userId,
                             @Valid @RequestBody UpdateRoleRequest request,
                             Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        String role = request.role().trim().toUpperCase(Locale.ROOT);
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported platform role.");
        }

        String actor = authentication.getName();
        String targetEmail = jdbc.query(
                "SELECT email FROM users WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString("email") : null, userId);
        if (targetEmail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User account was not found.");
        }

        if ("ADMIN".equalsIgnoreCase(targetEmail) && !actor.equalsIgnoreCase(targetEmail)) {
            // No special-cased admin account; continue with normal role checks.
        }
        if ("ADMIN".equals(role)) {
            jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, userId);
        } else {
            Integer adminCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE role = 'ADMIN'", Integer.class);
            String currentRole = jdbc.queryForObject(
                    "SELECT role FROM users WHERE id = ?", String.class, userId);
            if ("ADMIN".equalsIgnoreCase(currentRole) && adminCount != null && adminCount <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The last platform administrator cannot be demoted.");
            }
            jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, userId);
        }

        return jdbc.queryForObject("""
                SELECT id, full_name, email, phone, role, created_at
                FROM users WHERE id = ?
                """,
                (rs, row) -> new AdminUserView(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getObject("created_at", OffsetDateTime.class)), userId);
    }

    public record UpdateRoleRequest(@NotBlank String role) {}
    public record AdminUserView(UUID id, String fullName, String email, String phone,
                                String role, OffsetDateTime createdAt) {}
}
