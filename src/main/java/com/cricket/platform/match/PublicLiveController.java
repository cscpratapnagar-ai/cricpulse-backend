package com.cricket.platform.match;

import com.cricket.platform.scoring.GetLiveScore;
import com.cricket.platform.scoring.GetScorecard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Public read-only live score endpoints. No scorer authentication is required. */
@RestController
@RequestMapping("/api/public")
public class PublicLiveController {
    private final JdbcTemplate jdbc;
    private final GetLiveScore getLiveScore;
    private final GetScorecard getScorecard;

    public PublicLiveController(JdbcTemplate jdbc, GetLiveScore getLiveScore, GetScorecard getScorecard) {
        this.jdbc = jdbc;
        this.getLiveScore = getLiveScore;
        this.getScorecard = getScorecard;
    }

    @GetMapping("/matches/{matchId}")
    public PublicMatch match(@PathVariable UUID matchId) {
        return jdbc.queryForObject("""
                SELECT m.id, m.name, m.team_a_id, m.team_b_id,
                       ta.name AS team_a_name, tb.name AS team_b_name,
                       m.format, m.status, m.scheduled_at
                FROM matches m
                JOIN teams ta ON ta.id = m.team_a_id
                JOIN teams tb ON tb.id = m.team_b_id
                WHERE m.id = ?
                """, (rs, row) -> new PublicMatch(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("team_a_id", UUID.class), rs.getObject("team_b_id", UUID.class),
                rs.getString("team_a_name"), rs.getString("team_b_name"),
                rs.getString("format"), rs.getString("status"),
                rs.getObject("scheduled_at", java.time.OffsetDateTime.class)), matchId);
    }

    @GetMapping("/matches/{matchId}/scorecard")
    public List<GetScorecard.Scorecard> scorecard(@PathVariable UUID matchId) {
        return jdbc.queryForList(
                "SELECT id FROM innings WHERE match_id = ? ORDER BY innings_number",
                UUID.class, matchId
        ).stream().map(getScorecard::execute).toList();
    }

    @GetMapping("/matches/{matchId}/current-innings")
    public Current current(@PathVariable UUID matchId) {
        return jdbc.query("""
                SELECT i.id, i.innings_number, i.batting_team_id, i.bowling_team_id,
                       i.total_runs, i.wickets, i.legal_balls, i.status,
                       i.striker_id, i.non_striker_id, i.current_bowler_id
                FROM innings i
                JOIN matches m ON m.id = i.match_id
                WHERE i.match_id = ?
                  AND (i.id = m.current_innings_id OR m.current_innings_id IS NULL)
                ORDER BY i.innings_number DESC
                LIMIT 1
                """, (rs, row) -> new Current(
                rs.getObject("id", UUID.class), rs.getInt("innings_number"),
                rs.getObject("batting_team_id", UUID.class), rs.getObject("bowling_team_id", UUID.class),
                rs.getInt("total_runs"), rs.getInt("wickets"), rs.getInt("legal_balls"),
                rs.getString("status"), rs.getObject("striker_id", UUID.class),
                rs.getObject("non_striker_id", UUID.class), rs.getObject("current_bowler_id", UUID.class)), matchId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No innings found for match"));
    }

    @GetMapping("/innings/{inningsId}")
    public GetLiveScore.Score score(@PathVariable UUID inningsId) {
        return getLiveScore.execute(inningsId);
    }

    public record PublicMatch(UUID id, String name, UUID teamAId, UUID teamBId, String teamAName,
                              String teamBName, String format, String status, java.time.OffsetDateTime scheduledAt) {}

    public record Current(UUID inningsId, int inningsNumber, UUID battingTeamId, UUID bowlingTeamId,
                          int runs, int wickets, int legalBalls, String status,
                          UUID strikerId, UUID nonStrikerId, UUID currentBowlerId) {}
}
