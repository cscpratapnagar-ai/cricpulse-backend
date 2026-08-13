package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InningsLifecycle {
    private final JdbcTemplate jdbc;

    public InningsLifecycle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Completion evaluate(UUID inningsId) {
        State state = jdbc.queryForObject(
                """
                SELECT total_runs, wickets, legal_balls, total_overs, target_runs, status
                FROM innings
                WHERE id = ?
                """,
                (rs, row) -> new State(
                        rs.getInt("total_runs"),
                        rs.getInt("wickets"),
                        rs.getInt("legal_balls"),
                        (Integer) rs.getObject("total_overs"),
                        (Integer) rs.getObject("target_runs"),
                        rs.getString("status")
                ),
                inningsId
        );

        if (state == null || !"LIVE".equals(state.status())) {
            return new Completion(false, state == null ? null : state.status(), null);
        }

        String reason = completionReason(state);
        if (reason == null) {
            return new Completion(false, "LIVE", null);
        }

        jdbc.update(
                "UPDATE innings SET status = 'COMPLETED' WHERE id = ? AND status = 'LIVE'",
                inningsId
        );

        return new Completion(true, "COMPLETED", reason);
    }

    private String completionReason(State state) {
        if (state.targetRuns() != null && state.totalRuns() >= state.targetRuns()) {
            return "TARGET_REACHED";
        }
        if (state.wickets() >= 10) {
            return "ALL_OUT";
        }
        if (state.totalOvers() != null && state.totalOvers() > 0
                && state.legalBalls() >= state.totalOvers() * 6) {
            return "OVERS_COMPLETED";
        }
        return null;
    }

    private record State(
            int totalRuns,
            int wickets,
            int legalBalls,
            Integer totalOvers,
            Integer targetRuns,
            String status
    ) {}

    public record Completion(boolean completed, String status, String reason) {}
}
