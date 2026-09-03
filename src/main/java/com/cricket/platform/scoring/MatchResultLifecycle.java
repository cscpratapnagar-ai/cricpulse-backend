package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class MatchResultLifecycle {
    private final JdbcTemplate jdbc;

    public MatchResultLifecycle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Evaluation evaluate(UUID inningsId) {
        State state = jdbc.queryForObject(
                """
                SELECT i.match_id, i.innings_number, i.status, i.total_runs, i.wickets,
                       i.target_runs, m.team_a_id, m.team_b_id, m.status AS match_status
                FROM innings i
                JOIN matches m ON m.id = i.match_id
                WHERE i.id = ?
                FOR UPDATE OF i, m
                """,
                (rs, row) -> new State(
                        rs.getObject("match_id", UUID.class),
                        rs.getInt("innings_number"),
                        rs.getString("status"),
                        rs.getInt("total_runs"),
                        rs.getInt("wickets"),
                        (Integer) rs.getObject("target_runs"),
                        rs.getObject("team_a_id", UUID.class),
                        rs.getObject("team_b_id", UUID.class),
                        rs.getString("match_status")
                ),
                inningsId
        );

        if (state == null || !"COMPLETED".equals(state.status())) {
            return new Evaluation(EventType.DELIVERY_RECORDED, null, null, null);
        }

        if (state.inningsNumber() == 1) {
            return new Evaluation(EventType.INNINGS_COMPLETED, null, null, null);
        }

        if (state.inningsNumber() != 2) {
            return new Evaluation(EventType.INNINGS_COMPLETED, null, null, null);
        }

        FirstInnings first = jdbc.queryForObject(
                "SELECT batting_team_id, total_runs FROM innings WHERE match_id = ? AND innings_number = 1",
                (rs, row) -> new FirstInnings(
                        rs.getObject("batting_team_id", UUID.class),
                        rs.getInt("total_runs")
                ),
                state.matchId()
        );

        if (first == null) {
            throw new IllegalStateException("First innings is required before calculating the match result");
        }

        UUID winner;
        String resultType;
        int margin;
        String summary;

        if (state.targetRuns() != null && state.totalRuns() >= state.targetRuns()) {
            winner = state.battingTeamId(state.teamAId(), state.teamBId(), first.battingTeamId());
            resultType = "WIN_BY_WICKETS";
            margin = Math.max(0, 10 - state.wickets());
            summary = "won by " + margin + " wicket" + (margin == 1 ? "" : "s");
        } else if (state.totalRuns() == first.totalRuns()) {
            winner = null;
            resultType = "TIE";
            margin = 0;
            summary = "Match tied";
        } else {
            winner = first.battingTeamId();
            resultType = "WIN_BY_RUNS";
            margin = Math.max(0, first.totalRuns() - state.totalRuns());
            summary = "won by " + margin + " run" + (margin == 1 ? "" : "s");
        }

        if (!"COMPLETED".equals(state.matchStatus())) {
            jdbc.update(
                    """
                    UPDATE matches
                    SET status = 'COMPLETED', current_innings_id = ?, winner_team_id = ?,
                        result_type = ?, result_margin = ?, result_summary = ?
                    WHERE id = ?
                    """,
                    inningsId, winner, resultType, margin, summary, state.matchId()
            );
        }

        return new Evaluation(EventType.MATCH_RESULT, resultType, winner, margin);
    }

    public enum EventType {
        DELIVERY_RECORDED,
        INNINGS_COMPLETED,
        MATCH_RESULT
    }

    public record Evaluation(EventType eventType, String resultType, UUID winnerTeamId, Integer margin) {}

    private record FirstInnings(UUID battingTeamId, int totalRuns) {}

    private record State(
            UUID matchId,
            int inningsNumber,
            String status,
            int totalRuns,
            int wickets,
            Integer targetRuns,
            UUID teamAId,
            UUID teamBId,
            String matchStatus
    ) {
        UUID battingTeamId(UUID teamAId, UUID teamBId, UUID firstBattingTeamId) {
            return firstBattingTeamId.equals(teamAId) ? teamBId : teamAId;
        }
    }
}
