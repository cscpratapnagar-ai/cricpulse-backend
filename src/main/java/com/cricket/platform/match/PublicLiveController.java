package com.cricket.platform.match;

import com.cricket.platform.scoring.GetLiveScore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public read-only live score endpoints. No scorer authentication is required. */
@RestController
@RequestMapping("/api/public")
public class PublicLiveController {
    private final JdbcTemplate jdbc;
    private final GetLiveScore getLiveScore;

    public PublicLiveController(JdbcTemplate jdbc, GetLiveScore getLiveScore) {
        this.jdbc = jdbc;
        this.getLiveScore = getLiveScore;
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

    public record Current(UUID inningsId, int inningsNumber, UUID battingTeamId, UUID bowlingTeamId,
                          int runs, int wickets, int legalBalls, String status,
                          UUID strikerId, UUID nonStrikerId, UUID currentBowlerId) {}
}
