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
    private final GetLiveScore getLiveScore;

    public StartInnings(JdbcTemplate jdbc, GetLiveScore getLiveScore) {
        this.jdbc = jdbc;
        this.getLiveScore = getLiveScore;
    }

    @Transactional
    public InningsResponse execute(Request request) {
        MatchState match = jdbc.queryForObject(
                """
                SELECT id, team_a_id, team_b_id, total_overs, status,
                       toss_winner_team_id, toss_decision
                FROM matches WHERE id = ? FOR UPDATE
                """,
                (rs, row) -> new MatchState(
                        rs.getObject("id", UUID.class), rs.getObject("team_a_id", UUID.class),
                        rs.getObject("team_b_id", UUID.class), (Integer) rs.getObject("total_overs"),
                        rs.getString("status"), rs.getObject("toss_winner_team_id", UUID.class),
                        rs.getString("toss_decision")), request.matchId());

        if (match == null) throw new IllegalArgumentException("Match was not found");
        if (match.tossWinnerTeamId() == null || match.tossDecision() == null)
            throw new IllegalArgumentException("Toss must be recorded before starting an innings");
        if (match.totalOvers() == null || match.totalOvers() <= 0)
            throw new IllegalArgumentException("Match total overs are not configured");
        if (request.inningsNumber() < 1) throw new IllegalArgumentException("Innings number must be positive");

        UUID existingId = jdbc.query(
                "SELECT id FROM innings WHERE match_id = ? AND innings_number = ? ORDER BY id LIMIT 1",
                (rs, row) -> rs.getObject("id", UUID.class), request.matchId(), request.inningsNumber())
                .stream().findFirst().orElse(null);

        if (existingId != null) {
            ExistingState existing = jdbc.queryForObject(
                    """
                    SELECT id, batting_team_id, bowling_team_id, total_runs, wickets,
                           legal_balls, total_overs, target_runs, status
                    FROM innings WHERE id = ? FOR UPDATE
                    """,
                    (rs, row) -> new ExistingState(
                            rs.getObject("id", UUID.class), rs.getObject("batting_team_id", UUID.class),
                            rs.getObject("bowling_team_id", UUID.class), rs.getInt("total_runs"),
                            rs.getInt("wickets"), rs.getInt("legal_balls"),
                            (Integer) rs.getObject("total_overs"), (Integer) rs.getObject("target_runs"),
                            rs.getString("status")), existingId);

            if (existing == null) throw new IllegalArgumentException("Existing innings could not be loaded");
            if (!request.battingTeamId().equals(existing.battingTeamId()))
                throw new IllegalArgumentException("Batting team does not match the existing innings");

            if ("LIVE".equals(existing.status())) {
                jdbc.update("UPDATE matches SET status = 'LIVE', current_innings_id = ? WHERE id = ?", existing.id(), request.matchId());
                GetLiveScore.Score live = getLiveScore.execute(existing.id());
                return InningsResponse.fromLiveScore(live, existing);
            }

            throw new IllegalArgumentException("This innings is already completed");
        }

        UUID expectedBattingTeamId;
        if (request.inningsNumber() == 1) {
            expectedBattingTeamId = "BAT".equals(match.tossDecision())
                    ? match.tossWinnerTeamId() : oppositeTeam(match.tossWinnerTeamId(), match);
        } else {
            if (!hasCompletedPreviousInnings(request.matchId(), request.inningsNumber()))
                throw new IllegalArgumentException("Previous innings must be completed before starting this innings");
            expectedBattingTeamId = oppositeTeam(match.tossWinnerTeamId(), match);
        }

        if (!request.battingTeamId().equals(expectedBattingTeamId))
            throw new IllegalArgumentException("Batting team does not match the toss/innings order");

        UUID bowlingTeamId = oppositeTeam(request.battingTeamId(), match);
        UUID id = UUID.randomUUID();
        Integer targetRuns = null;
        if (request.inningsNumber() > 1) {
            targetRuns = jdbc.queryForObject(
                    "SELECT total_runs + 1 FROM innings WHERE match_id = ? AND innings_number = ?",
                    Integer.class, request.matchId(), request.inningsNumber() - 1);
        }

        if (request.strikerId() == null || request.nonStrikerId() == null || request.currentBowlerId() == null)
            throw new IllegalArgumentException("Striker, non-striker and opening bowler are required");
        if (request.strikerId().equals(request.nonStrikerId()))
            throw new IllegalArgumentException("Striker and non-striker must be different");

        jdbc.update(
                """
                INSERT INTO innings(
                    id, match_id, innings_number, batting_team_id, bowling_team_id,
                    total_runs, wickets, legal_balls, total_overs, status, target_runs,
                    current_over, current_ball, striker_id, non_striker_id, current_bowler_id,
                    declared, is_super_over
                ) VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, 'LIVE', ?, 0, 0, ?, ?, ?, FALSE, FALSE)
                """,
                id, request.matchId(), request.inningsNumber(), request.battingTeamId(), bowlingTeamId,
                match.totalOvers(), targetRuns, request.strikerId(), request.nonStrikerId(), request.currentBowlerId());

        jdbc.update("UPDATE matches SET status = 'LIVE', current_innings_id = ? WHERE id = ?", id, request.matchId());

        // Start the first partnership immediately so the scorer and resume viewer have state from ball zero.
        jdbc.update(
                "INSERT INTO partnerships(innings_id, wicket_number, batter_one_id, batter_two_id, runs, balls, is_current) VALUES (?, 0, ?, ?, 0, 0, TRUE)",
                id, request.strikerId(), request.nonStrikerId());

        return new InningsResponse(id, request.matchId(), request.inningsNumber(), request.battingTeamId(), bowlingTeamId,
                match.totalOvers(), targetRuns, 0, 0, 0, "LIVE",
                request.strikerId(), request.nonStrikerId(), request.currentBowlerId(),
                new GetLiveScore.Partnership(request.strikerId(), request.nonStrikerId(), 0, 0));
    }

    private UUID oppositeTeam(UUID teamId, MatchState match) {
        return teamId.equals(match.teamAId()) ? match.teamBId() : match.teamAId();
    }

    private boolean hasCompletedPreviousInnings(UUID matchId, int inningsNumber) {
        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM innings WHERE match_id = ? AND innings_number = ? AND status = 'COMPLETED'",
                Integer.class, matchId, inningsNumber - 1);
        return completed != null && completed > 0;
    }

    private record MatchState(UUID id, UUID teamAId, UUID teamBId, Integer totalOvers,
                              String status, UUID tossWinnerTeamId, String tossDecision) {}
    private record ExistingState(UUID id, UUID battingTeamId, UUID bowlingTeamId, int runs,
                                 int wickets, int legalBalls, Integer totalOvers,
                                 Integer targetRuns, String status) {}

    public record Request(@NotNull UUID matchId, @NotNull @Min(1) Integer inningsNumber,
                          @NotNull UUID battingTeamId, UUID strikerId, UUID nonStrikerId,
                          UUID currentBowlerId) {}

    public record InningsResponse(UUID id, UUID matchId, int inningsNumber,
                                  UUID battingTeamId, UUID bowlingTeamId, int totalOvers,
                                  Integer targetRuns, int runs, int wickets, int legalBalls,
                                  String status, UUID strikerId, UUID nonStrikerId,
                                  UUID currentBowlerId, GetLiveScore.Partnership partnership) {
        static InningsResponse fromLiveScore(GetLiveScore.Score live, ExistingState existing) {
            return new InningsResponse(live.inningsId(), live.matchId(), live.inningsNumber(),
                    existing.battingTeamId(), existing.bowlingTeamId(),
                    live.totalOvers() == null ? 0 : live.totalOvers(), live.targetRuns(),
                    live.runs(), live.wickets(), live.legalBalls(), live.status(),
                    live.strikerId(), live.nonStrikerId(), live.currentBowlerId(), live.partnership());
        }
    }
}
