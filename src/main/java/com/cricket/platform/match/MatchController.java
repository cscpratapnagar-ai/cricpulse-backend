package com.cricket.platform.match;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchController {
    private final CreateMatch createMatch;
    private final GetMatch getMatch;
    private final RecordToss recordToss;
    private final JdbcTemplate jdbc;

    public MatchController(CreateMatch createMatch, GetMatch getMatch, RecordToss recordToss, JdbcTemplate jdbc) {
        this.createMatch = createMatch;
        this.getMatch = getMatch;
        this.recordToss = recordToss;
        this.jdbc = jdbc;
    }

    @PostMapping
    CreateMatch.MatchResponse create(@Valid @RequestBody CreateMatch.Request request,
                                     Authentication authentication) {
        return createMatch.execute(request, authentication);
    }

    @GetMapping("/{id}")
    GetMatch.MatchView get(@PathVariable UUID id) { return getMatch.execute(id); }

    @GetMapping("/{id}/toss")
    TossResponse getToss(@PathVariable UUID id, Authentication authentication) {
        requireAuthenticated(authentication);
        MatchTeams teams = jdbc.queryForObject("""
                SELECT team_a_id, team_b_id FROM matches WHERE id = ?
                """, (rs, row) -> new MatchTeams(
                rs.getObject("team_a_id", UUID.class),
                rs.getObject("team_b_id", UUID.class)), id);
        if (teams == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Match was not found");
        if (!canManageEitherTeam(teams.teamAId(), teams.teamBId(), authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a team owner, manager or captain can view the toss setup.");
        }

        return jdbc.queryForObject("""
                SELECT toss_winner_team_id, toss_decision
                FROM matches
                WHERE id = ?
                """, (rs, row) -> {
            UUID winner = rs.getObject("toss_winner_team_id", UUID.class);
            String decision = rs.getString("toss_decision");
            if (winner == null || decision == null) {
                return new TossResponse(id, null, null, null, null, false);
            }
            UUID otherTeamId = winner.equals(teams.teamAId()) ? teams.teamBId() : teams.teamAId();
            UUID battingTeamId = "BAT".equalsIgnoreCase(decision) ? winner : otherTeamId;
            UUID bowlingTeamId = "BOWL".equalsIgnoreCase(decision) ? winner : otherTeamId;
            return new TossResponse(id, winner, decision, battingTeamId, bowlingTeamId, true);
        }, id);
    }

    @PostMapping("/{id}/toss")
    RecordToss.TossResponse toss(@PathVariable UUID id,
                                 @Valid @RequestBody TossRequest request,
                                 Authentication authentication) {
        requireAuthenticated(authentication);
        if (!id.equals(request.matchId())) {
            throw new IllegalArgumentException("Match id in URL and request body must match");
        }
        MatchTeams teams = jdbc.queryForObject("""
                SELECT team_a_id, team_b_id FROM matches WHERE id = ?
                """, (rs, row) -> new MatchTeams(
                rs.getObject("team_a_id", UUID.class),
                rs.getObject("team_b_id", UUID.class)), id);
        if (teams == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Match was not found");
        if (!canManageEitherTeam(teams.teamAId(), teams.teamBId(), authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a team owner, manager or captain can record the toss.");
        }
        return recordToss.execute(new RecordToss.Request(id, request.winnerTeamId(), request.decision()));
    }

    @GetMapping
    List<GetMatch.MatchView> list() {
        return jdbc.query("""
                        SELECT m.id,
                               m.name,
                               m.team_a_id,
                               m.team_b_id,
                               ta.name AS team_a_name,
                               tb.name AS team_b_name,
                               m.format,
                               m.status,
                               m.scheduled_at
                        FROM matches m
                        JOIN teams ta ON ta.id = m.team_a_id
                        JOIN teams tb ON tb.id = m.team_b_id
                        ORDER BY m.scheduled_at NULLS LAST, m.created_at DESC
                        """,
                (rs, row) -> new GetMatch.MatchView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("team_a_id", UUID.class), rs.getObject("team_b_id", UUID.class),
                        rs.getString("team_a_name"), rs.getString("team_b_name"),
                        rs.getString("format"), rs.getString("status"), rs.getObject("scheduled_at", java.time.OffsetDateTime.class)));
    }

    private boolean canManageEitherTeam(UUID teamAId, UUID teamBId, Authentication authentication) {
        return canManageTeam(teamAId, authentication) || canManageTeam(teamBId, authentication);
    }

    private boolean canManageTeam(UUID teamId, Authentication authentication) {
        String principal = authentication.getName();
        Integer owner = jdbc.queryForObject("""
                SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id
                WHERE t.id = ? AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                """, Integer.class, teamId, principal, principal);
        if (owner != null && owner > 0) return true;

        Integer manager = jdbc.queryForObject("""
                SELECT COUNT(*) FROM team_members tm
                JOIN players p ON p.id = tm.player_id
                JOIN users u ON u.id = p.user_id
                WHERE tm.team_id = ? AND tm.role IN ('MANAGER', 'CAPTAIN')
                  AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                """, Integer.class, teamId, principal, principal);
        return manager != null && manager > 0;
    }

    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
    }

    private record MatchTeams(UUID teamAId, UUID teamBId) {}

    public record TossRequest(UUID matchId, UUID winnerTeamId, String decision) {}

    public record TossResponse(UUID matchId, UUID tossWinnerTeamId, String decision,
                               UUID battingTeamId, UUID bowlingTeamId, boolean recorded) {}
}
