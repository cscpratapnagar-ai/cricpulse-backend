package com.cricket.platform.scoring;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class StartInnings {
    private final JdbcTemplate jdbc;

    public StartInnings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public InningsResponse execute(Request request) {
        MatchState match = jdbc.queryForObject(
                """
                SELECT id, team_a_id, team_b_id, total_overs, status,
                       toss_winner_team_id, toss_decision
                FROM matches
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new MatchState(
                        rs.getObject("id", UUID.class),
                        rs.getObject("team_a_id", UUID.class),
                        rs.getObject("team_b_id", UUID.class),
                        (Integer) rs.getObject("total_overs"),
                        rs.getString("status"),
                        rs.getObject("toss_winner_team_id", UUID.class),
                        rs.getString("toss_decision")
                ),
                request.matchId()
        );

        if (match == null) {
            throw new IllegalArgumentException("Match was not found");
        }

        if (match.tossWinnerTeamId() == null || match.tossDecision() == null) {
            throw new IllegalArgumentException("Toss must be recorded before starting an innings");
        }

        if (match.totalOvers() == null || match.totalOvers() <= 0) {
            throw new IllegalArgumentException("Match total overs are not configured");
        }

        if (request.inningsNumber() < 1) {
            throw new IllegalArgumentException("Innings number must be positive");
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM innings WHERE match_id = ? AND innings_number = ?",
                Integer.class,
                request.matchId(),
                request.inningsNumber()
        );

        if (count != null && count > 0) {
            throw new IllegalArgumentException("This innings already exists");
        }

        UUID expectedBattingTeamId;
        if (request.inningsNumber() == 1) {
            expectedBattingTeamId = "BAT".equals(match.tossDecision())
                    ? match.tossWinnerTeamId()
                    : oppositeTeam(match.tossWinnerTeamId(), match);
        } else {
            if (!hasCompletedPreviousInnings(request.matchId(), request.inningsNumber())) {
                throw new IllegalArgumentException("Previous innings must be completed before starting this innings");
            }
            expectedBattingTeamId = oppositeTeam(match.tossWinnerTeamId(), match);
        }

        if (!request.battingTeamId().equals(expectedBattingTeamId)) {
            throw new IllegalArgumentException("Batting team does not match the toss/innings order");
        }

        UUID bowlingTeamId = oppositeTeam(request.battingTeamId(), match);
        UUID id = UUID.randomUUID();
        Integer targetRuns = null;

        if (request.inningsNumber() > 1) {
            targetRuns = jdbc.queryForObject(
                    "SELECT total_runs + 1 FROM innings WHERE match_id = ? AND innings_number = ?",
                    Integer.class,
                    request.matchId(),
                    request.inningsNumber() - 1
            );
        }

        jdbc.update(
                """
                INSERT INTO innings(
                    id, match_id, innings_number, batting_team_id, bowling_team_id,
                    total_runs, wickets, legal_balls, total_overs, status, target_runs,
                    current_over, current_ball, declared, is_super_over
                )
                VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, 'LIVE', ?, 0, 0, FALSE, FALSE)
                """,
                id,
                request.matchId(),
                request.inningsNumber(),
                request.battingTeamId(),
                bowlingTeamId,
                match.totalOvers(),
                targetRuns
        );

        jdbc.update(
                "UPDATE matches SET status = 'LIVE', current_innings_id = ? WHERE id = ?",
                id,
                request.matchId()
        );

        return new InningsResponse(
                id,
                request.matchId(),
                request.inningsNumber(),
                request.battingTeamId(),
                bowlingTeamId,
                match.totalOvers(),
                targetRuns,
                0,
                0,
                0,
                "LIVE"
        );
    }

    private UUID oppositeTeam(UUID teamId, MatchState match) {
        return teamId.equals(match.teamAId()) ? match.teamBId() : match.teamAId();
    }

    private boolean hasCompletedPreviousInnings(UUID matchId, int inningsNumber) {
        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM innings WHERE match_id = ? AND innings_number = ? AND status = 'COMPLETED'",
                Integer.class,
                matchId,
                inningsNumber - 1
        );
        return completed != null && completed > 0;
    }

    private record MatchState(
            UUID id,
            UUID teamAId,
            UUID teamBId,
            Integer totalOvers,
            String status,
            UUID tossWinnerTeamId,
            String tossDecision
    ) {}

    public record Request(
            @NotNull UUID matchId,
            @NotNull @Min(1) Integer inningsNumber,
            @NotNull UUID battingTeamId
    ) {}

    public record InningsResponse(
            UUID id,
            UUID matchId,
            int inningsNumber,
            UUID battingTeamId,
            UUID bowlingTeamId,
            int totalOvers,
            Integer targetRuns,
            int runs,
            int wickets,
            int legalBalls,
            String status
    ) {}
}
