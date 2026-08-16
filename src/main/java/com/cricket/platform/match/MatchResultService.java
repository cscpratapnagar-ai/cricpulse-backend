package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class MatchResultService {
    private final JdbcTemplate jdbc;

    public MatchResultService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Result execute(UUID matchId) {
        MatchState match = jdbc.queryForObject(
                """
                SELECT m.id, m.name, m.format, m.status,
                       m.team_a_id, ta.name AS team_a_name,
                       m.team_b_id, tb.name AS team_b_name,
                       m.total_overs, m.winning_team_id, m.result_type, m.result_text
                FROM matches m
                JOIN teams ta ON ta.id = m.team_a_id
                JOIN teams tb ON tb.id = m.team_b_id
                WHERE m.id = ?
                FOR UPDATE
                """,
                (rs, row) -> new MatchState(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("format"),
                        rs.getString("status"),
                        rs.getObject("team_a_id", UUID.class),
                        rs.getString("team_a_name"),
                        rs.getObject("team_b_id", UUID.class),
                        rs.getString("team_b_name"),
                        (Integer) rs.getObject("total_overs"),
                        rs.getObject("winning_team_id", UUID.class),
                        rs.getString("result_type"),
                        rs.getString("result_text")
                ),
                matchId
        );

        if (match == null) {
            throw new IllegalArgumentException("Match was not found");
        }

        InningsScore first = findInnings(matchId, 1);
        InningsScore second = findInnings(matchId, 2);

        if (first == null || second == null) {
            throw new IllegalArgumentException("Both innings must exist before calculating the match result");
        }

        if (!"COMPLETED".equals(first.status()) || !"COMPLETED".equals(second.status())) {
            throw new IllegalArgumentException("Both innings must be completed before calculating the match result");
        }

        if (match.resultType() != null && match.resultText() != null) {
            return toResult(match, first, second);
        }

        UUID winner = null;
        String resultType;
        String resultText;

        if (second.runs() > first.runs()) {
            winner = second.battingTeamId();
            int wicketsRemaining = Math.max(0, 10 - second.wickets());
            resultType = "WIN";
            resultText = second.battingTeamName() + " won by " + wicketsRemaining + " wickets";
        } else if (second.runs() < first.runs()) {
            winner = first.battingTeamId();
            resultType = "WIN";
            resultText = first.battingTeamName() + " won by " + (first.runs() - second.runs()) + " runs";
        } else {
            resultType = "TIE";
            resultText = "Match tied";
        }

        jdbc.update(
                """
                UPDATE matches
                SET status = 'COMPLETED',
                    winning_team_id = ?,
                    result_type = ?,
                    result_text = ?,
                    completed_at = COALESCE(completed_at, ?),
                    current_innings_id = NULL
                WHERE id = ?
                """,
                winner,
                resultType,
                resultText,
                OffsetDateTime.now(),
                matchId
        );

        return new Result(
                match.id(), match.name(), match.format(), "COMPLETED",
                resultType, resultText, winner,
                new TeamScore(first.battingTeamId(), first.battingTeamName(), first.runs(), first.wickets(), first.legalBalls(), first.totalOvers()),
                new TeamScore(second.battingTeamId(), second.battingTeamName(), second.runs(), second.wickets(), second.legalBalls(), second.totalOvers())
        );
    }

    private InningsScore findInnings(UUID matchId, int inningsNumber) {
        return jdbc.query(
                """
                SELECT i.innings_number, i.batting_team_id, t.name AS batting_team_name,
                       i.total_runs, i.wickets, i.legal_balls, i.total_overs, i.status
                FROM innings i
                JOIN teams t ON t.id = i.batting_team_id
                WHERE i.match_id = ? AND i.innings_number = ?
                LIMIT 1
                """,
                (rs, row) -> new InningsScore(
                        rs.getInt("innings_number"),
                        rs.getObject("batting_team_id", UUID.class),
                        rs.getString("batting_team_name"),
                        rs.getInt("total_runs"),
                        rs.getInt("wickets"),
                        rs.getInt("legal_balls"),
                        (Integer) rs.getObject("total_overs"),
                        rs.getString("status")
                ),
                matchId, inningsNumber
        ).stream().findFirst().orElse(null);
    }

    private Result toResult(MatchState match, InningsScore first, InningsScore second) {
        return new Result(
                match.id(), match.name(), match.format(), match.status(),
                match.resultType(), match.resultText(), match.winningTeamId(),
                new TeamScore(first.battingTeamId(), first.battingTeamName(), first.runs(), first.wickets(), first.legalBalls(), first.totalOvers()),
                new TeamScore(second.battingTeamId(), second.battingTeamName(), second.runs(), second.wickets(), second.legalBalls(), second.totalOvers())
        );
    }

    private record MatchState(
            UUID id, String name, String format, String status,
            UUID teamAId, String teamAName, UUID teamBId, String teamBName,
            Integer totalOvers, UUID winningTeamId, String resultType, String resultText
    ) {}

    private record InningsScore(
            int inningsNumber, UUID battingTeamId, String battingTeamName,
            int runs, int wickets, int legalBalls, Integer totalOvers, String status
    ) {}

    public record TeamScore(
            UUID teamId, String teamName, int runs, int wickets,
            int legalBalls, Integer totalOvers
    ) {}

    public record Result(
            UUID matchId, String matchName, String format, String status,
            String resultType, String resultText, UUID winningTeamId,
            TeamScore firstInnings, TeamScore secondInnings
    ) {}
}
