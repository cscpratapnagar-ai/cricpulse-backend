package com.cricket.platform.match;

import com.cricket.platform.scoring.GetLiveScore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Public, read-only broadcast state for OBS/TV overlays.
 */
@RestController
@RequestMapping("/api/public")
public class PublicBroadcastController {
    private final JdbcTemplate jdbc;
    private final GetLiveScore getLiveScore;

    public PublicBroadcastController(JdbcTemplate jdbc, GetLiveScore getLiveScore) {
        this.jdbc = jdbc;
        this.getLiveScore = getLiveScore;
    }

    @GetMapping("/matches/{matchId}/broadcast-state")
    public BroadcastState state(@PathVariable UUID matchId) {
        MatchMeta match = jdbc.queryForObject("""
                SELECT m.id, m.name, m.format, m.status, m.total_overs,
                       m.team_a_id, m.team_b_id,
                       ta.name AS team_a_name, tb.name AS team_b_name,
                       m.current_innings_id, m.winning_team_id,
                       m.result_type, m.result_text, m.completed_at
                FROM matches m
                JOIN teams ta ON ta.id = m.team_a_id
                JOIN teams tb ON tb.id = m.team_b_id
                WHERE m.id = ?
                """, (rs, row) -> new MatchMeta(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("format"),
                rs.getString("status"),
                (Integer) rs.getObject("total_overs"),
                rs.getObject("team_a_id", UUID.class),
                rs.getObject("team_b_id", UUID.class),
                rs.getString("team_a_name"),
                rs.getString("team_b_name"),
                rs.getObject("current_innings_id", UUID.class),
                rs.getObject("winning_team_id", UUID.class),
                rs.getString("result_type"),
                rs.getString("result_text"),
                rs.getObject("completed_at", OffsetDateTime.class)
        ), matchId);

        List<InningsState> innings = jdbc.query("""
                SELECT id, innings_number, batting_team_id, bowling_team_id,
                       target_runs, status, declared, is_super_over
                FROM innings
                WHERE match_id = ?
                ORDER BY innings_number
                """, (rs, row) -> {
            UUID inningsId = rs.getObject("id", UUID.class);
            GetLiveScore.Score score = getLiveScore.execute(inningsId);
            return new InningsState(
                    score,
                    rs.getObject("batting_team_id", UUID.class),
                    rs.getObject("bowling_team_id", UUID.class),
                    (Integer) rs.getObject("target_runs"),
                    rs.getBoolean("declared"),
                    rs.getBoolean("is_super_over")
            );
        }, matchId);

        return new BroadcastState(match, innings);
    }

    public record BroadcastState(MatchMeta match, List<InningsState> innings) {}

    public record MatchMeta(
            UUID id,
            String name,
            String format,
            String status,
            Integer totalOvers,
            UUID teamAId,
            UUID teamBId,
            String teamAName,
            String teamBName,
            UUID currentInningsId,
            UUID winningTeamId,
            String resultType,
            String resultText,
            OffsetDateTime completedAt
    ) {}

    public record InningsState(
            GetLiveScore.Score score,
            UUID battingTeamId,
            UUID bowlingTeamId,
            Integer targetRuns,
            boolean declared,
            boolean superOver
    ) {}
}
