package com.cricket.platform.match;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class CreateMatch {
    private static final Map<String, Integer> FORMAT_OVERS = Map.of(
            "T10", 10,
            "T20", 20,
            "ODI", 50
    );

    private final JdbcTemplate jdbc;

    public CreateMatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MatchResponse execute(Request request, Authentication authentication) {
        requireAuthenticated(authentication);
        requireTeam(request.teamAId());
        requireTeam(request.teamBId());

        if (request.teamAId().equals(request.teamBId())) {
            throw new IllegalArgumentException("A team cannot play against itself");
        }

        if (!canManageEitherTeam(request.teamAId(), request.teamBId(), authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a team owner, manager or captain can create a match for this team.");
        }

        String format = request.format().trim().toUpperCase(Locale.ROOT);
        int totalOvers = resolveOvers(format, request.totalOvers());

        UUID id = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO matches(
                    id, name, team_a_id, team_b_id, format,
                    total_overs, scheduled_at, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                """,
                id,
                request.name().trim(),
                request.teamAId(),
                request.teamBId(),
                format,
                totalOvers,
                request.scheduledAt()
        );

        return new MatchResponse(id, request.name().trim(), format, totalOvers, "SCHEDULED");
    }

    private boolean canManageEitherTeam(UUID teamAId, UUID teamBId, Authentication authentication) {
        return canManageTeam(teamAId, authentication) || canManageTeam(teamBId, authentication);
    }

    private boolean canManageTeam(UUID teamId, Authentication authentication) {
        String principal = authentication.getName();
        Integer owner = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM teams t
                JOIN users u ON u.id = t.owner_id
                WHERE t.id = ?
                  AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                """, Integer.class, teamId, principal, principal);
        if (owner != null && owner > 0) return true;

        Integer manager = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM team_members tm
                JOIN players p ON p.id = tm.player_id
                JOIN users u ON u.id = p.user_id
                WHERE tm.team_id = ?
                  AND tm.role IN ('MANAGER', 'CAPTAIN')
                  AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                """, Integer.class, teamId, principal, principal);
        return manager != null && manager > 0;
    }

    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
    }

    private int resolveOvers(String format, Integer customOvers) {
        Integer standardOvers = FORMAT_OVERS.get(format);

        if ("TEST".equals(format)) {
            throw new IllegalArgumentException("Test matches do not use a fixed overs limit");
        }

        if ("CUSTOM".equals(format)) {
            if (customOvers == null || customOvers < 1 || customOvers > 500) {
                throw new IllegalArgumentException("Custom match overs must be between 1 and 500");
            }
            return customOvers;
        }

        if (standardOvers == null) {
            throw new IllegalArgumentException("Unsupported match format. Use T10, T20, ODI or CUSTOM");
        }

        if (customOvers != null && !customOvers.equals(standardOvers)) {
            throw new IllegalArgumentException("Overs do not match the selected format");
        }

        return standardOvers;
    }

    private void requireTeam(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams WHERE id = ?",
                Integer.class,
                id
        );
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Team was not found: " + id);
        }
    }

    public record Request(
            @NotBlank String name,
            @NotNull UUID teamAId,
            @NotNull UUID teamBId,
            @NotBlank String format,
            @Min(1) Integer totalOvers,
            OffsetDateTime scheduledAt
    ) {}

    public record MatchResponse(
            UUID id,
            String name,
            String format,
            int totalOvers,
            String status
    ) {}
}
