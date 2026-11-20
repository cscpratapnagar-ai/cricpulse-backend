package com.cricket.platform.tournament;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only tournament summary for unauthenticated public viewers.
 * Never exposes owner, manager, player-account or staff-management data.
 */
@RestController
@RequestMapping("/api/public/tournaments")
public class PublicTournamentController {
    private final JdbcTemplate jdbc;
    private final GetTournamentAnalytics analytics;

    public PublicTournamentController(JdbcTemplate jdbc, GetTournamentAnalytics analytics) {
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    @GetMapping("/{tournamentId}")
    public PublicTournamentView get(@PathVariable UUID tournamentId) {
        GetTournamentAnalytics.TournamentAnalytics summary = analytics.get(tournamentId);
        List<PublicFixture> fixtures = jdbc.query("""
                SELECT tm.match_id, tm.fixture_number, tm.stage, m.status,
                       a.name AS team_a_name, b.name AS team_b_name,
                       CASE
                         WHEN COALESCE(m.winning_team_id, m.winner_team_id) = m.team_a_id THEN a.name
                         WHEN COALESCE(m.winning_team_id, m.winner_team_id) = m.team_b_id THEN b.name
                         ELSE NULL
                       END AS winner_name,
                       m.scheduled_at
                FROM tournament_matches tm
                JOIN matches m ON m.id = tm.match_id
                JOIN teams a ON a.id = m.team_a_id
                JOIN teams b ON b.id = m.team_b_id
                WHERE tm.tournament_id = ?
                ORDER BY COALESCE(tm.fixture_number, 999999), m.scheduled_at NULLS LAST
                """,
                (rs, row) -> new PublicFixture(
                        rs.getObject("match_id", UUID.class),
                        rs.getObject("fixture_number", Integer.class),
                        rs.getString("stage"),
                        rs.getString("status"),
                        rs.getString("team_a_name"),
                        rs.getString("team_b_name"),
                        rs.getString("winner_name"),
                        rs.getObject("scheduled_at", OffsetDateTime.class)),
                tournamentId);

        return new PublicTournamentView(
                summary.tournamentId(),
                summary.tournamentName(),
                summary.format(),
                summary.overs(),
                summary.status(),
                summary.totalFixtures(),
                summary.completedFixtures(),
                summary.scheduledFixtures(),
                summary.liveFixtures(),
                summary.completionPercentage(),
                summary.teams(),
                fixtures);
    }

    public record PublicTournamentView(
            UUID tournamentId,
            String tournamentName,
            String format,
            int overs,
            String status,
            int totalFixtures,
            int completedFixtures,
            int scheduledFixtures,
            int liveFixtures,
            java.math.BigDecimal completionPercentage,
            List<GetTournamentAnalytics.TeamAnalytics> pointsTable,
            List<PublicFixture> fixtures) {}

    public record PublicFixture(
            UUID matchId,
            Integer fixtureNumber,
            String stage,
            String status,
            String teamAName,
            String teamBName,
            String winnerName,
            OffsetDateTime scheduledAt) {}
}
