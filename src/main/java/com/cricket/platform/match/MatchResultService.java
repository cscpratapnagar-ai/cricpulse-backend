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
                       m.total_overs, m.winning_team_id, m.result_type, m.result_text,
                       m.toss_winner_team_id, m.toss_decision
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
                        rs.getString("result_text"),
                        rs.getObject("toss_winner_team_id", UUID.class),
                        rs.getString("toss_decision")
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

        validateMatchSetup(match, first, second);
        validateInnings(match, first);
        validateInnings(match, second);

        if (match.resultType() != null && match.resultText() != null) {
            return toResult(match, first, second);
        }

        UUID winner = null;
        String resultType;
        String resultText;
        String detailedResultType;
        int resultMargin;
        String resultSummary;

        if (second.runs() > first.runs()) {
            winner = second.battingTeamId();
            int wicketsRemaining = Math.max(0, 10 - second.wickets());
            resultType = "WIN";
            resultText = second.battingTeamName() + " won by " + wicketsRemaining + " wickets";
            detailedResultType = "WIN_BY_WICKETS";
            resultMargin = wicketsRemaining;
            resultSummary = resultText;
        } else if (second.runs() < first.runs()) {
            winner = first.battingTeamId();
            int runsMargin = first.runs() - second.runs();
            resultType = "WIN";
            resultText = first.battingTeamName() + " won by " + runsMargin + " runs";
            detailedResultType = "WIN_BY_RUNS";
            resultMargin = runsMargin;
            resultSummary = resultText;
        } else {
            resultType = "TIE";
            resultText = "Match tied";
            detailedResultType = "TIE";
            resultMargin = 0;
            resultSummary = resultText;
        }

        jdbc.update(
                """
                UPDATE matches
                SET status = 'COMPLETED',
                    winning_team_id = ?,
                    result_type = ?,
                    result_text = ?,
                    completed_at = COALESCE(completed_at, ?),
                    current_innings_id = NULL,
                    winner_team_id = ?,
                    result_margin = ?,
                    result_summary = ?
                WHERE id = ?
                """,
                winner,
                resultType,
                resultText,
                OffsetDateTime.now(),
                winner,
                resultMargin,
                resultSummary,
                matchId
        );

        return new Result(
                match.id(), match.name(), match.format(), "COMPLETED",
                detailedResultType, resultText, winner,
                new TeamScore(first.battingTeamId(), first.battingTeamName(), first.runs(), first.wickets(), first.legalBalls(), first.totalOvers()),
                new TeamScore(second.battingTeamId(), second.battingTeamName(), second.runs(), second.wickets(), second.legalBalls(), second.totalOvers())
        );
    }

    private void validateMatchSetup(MatchState match, InningsScore first, InningsScore second) {
        if (match.teamAId() == null || match.teamBId() == null || match.teamAId().equals(match.teamBId())) {
            throw new IllegalArgumentException("A match must have two different teams before calculating the result");
        }
        if (match.totalOvers() == null || match.totalOvers() <= 0) {
            throw new IllegalArgumentException("Match total overs must be configured before calculating the result");
        }
        if (match.tossWinnerTeamId() == null || match.tossDecision() == null || match.tossDecision().isBlank()) {
            throw new IllegalArgumentException("Toss must be recorded before calculating the match result");
        }
        if (!match.tossWinnerTeamId().equals(match.teamAId()) && !match.tossWinnerTeamId().equals(match.teamBId())) {
            throw new IllegalArgumentException("Toss winner must be one of the match teams");
        }
        if (!"BAT".equalsIgnoreCase(match.tossDecision()) && !"BOWL".equalsIgnoreCase(match.tossDecision())) {
            throw new IllegalArgumentException("Toss decision must be BAT or BOWL");
        }
        if (first.battingTeamId().equals(second.battingTeamId())) {
            throw new IllegalArgumentException("The two innings must have different batting teams");
        }
    }

    private void validateInnings(MatchState match, InningsScore innings) {
        if (!match.teamAId().equals(innings.battingTeamId()) && !match.teamBId().equals(innings.battingTeamId())) {
            throw new IllegalArgumentException("Innings batting team must belong to the match");
        }
        if (innings.runs() < 0) {
            throw new IllegalArgumentException("Innings runs cannot be negative");
        }
        if (innings.wickets() < 0 || innings.wickets() > 10) {
            throw new IllegalArgumentException("Innings wickets must be between 0 and 10");
        }
        int maxLegalBalls = match.totalOvers() * 6;
        if (innings.legalBalls() < 0 || innings.legalBalls() > maxLegalBalls) {
            throw new IllegalArgumentException("Innings legal balls exceed the configured match overs");
        }
        if (innings.totalOvers() == null || innings.totalOvers() < 0 || innings.totalOvers() > match.totalOvers()) {
            throw new IllegalArgumentException("Innings overs exceed the configured match overs");
        }
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
        String detailedResultType = switch (match.resultType()) {
            case "TIE" -> "TIE";
            case "WIN" -> second.runs() > first.runs() ? "WIN_BY_WICKETS" : "WIN_BY_RUNS";
            default -> match.resultType();
        };
        return new Result(
                match.id(), match.name(), match.format(), match.status(),
                detailedResultType, match.resultText(), match.winningTeamId(),
                new TeamScore(first.battingTeamId(), first.battingTeamName(), first.runs(), first.wickets(), first.legalBalls(), first.totalOvers()),
                new TeamScore(second.battingTeamId(), second.battingTeamName(), second.runs(), second.wickets(), second.legalBalls(), second.totalOvers())
        );
    }

    private record MatchState(
            UUID id, String name, String format, String status,
            UUID teamAId, String teamAName, UUID teamBId, String teamBName,
            Integer totalOvers, UUID winningTeamId, String resultType, String resultText,
            UUID tossWinnerTeamId, String tossDecision
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
