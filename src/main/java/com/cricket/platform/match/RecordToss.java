package com.cricket.platform.match;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Component
public class RecordToss {
    private final JdbcTemplate jdbc;

    public RecordToss(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public TossResponse execute(Request request) {
        MatchState match = jdbc.queryForObject(
                "SELECT team_a_id, team_b_id, status FROM matches WHERE id = ? FOR UPDATE",
                (rs, row) -> new MatchState(
                        rs.getObject("team_a_id", UUID.class),
                        rs.getObject("team_b_id", UUID.class),
                        rs.getString("status")
                ),
                request.matchId()
        );

        if (match == null) {
            throw new IllegalArgumentException("Match was not found");
        }

        if (!request.winnerTeamId().equals(match.teamAId()) && !request.winnerTeamId().equals(match.teamBId())) {
            throw new IllegalArgumentException("Toss winner must be one of the match teams");
        }

        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!decision.equals("BAT") && !decision.equals("BOWL")) {
            throw new IllegalArgumentException("Toss decision must be BAT or BOWL");
        }

        if ("LIVE".equals(match.status()) || "COMPLETED".equals(match.status())) {
            throw new IllegalArgumentException("Toss cannot be changed after the match has started");
        }

        jdbc.update(
                "UPDATE matches SET toss_winner_team_id = ?, toss_decision = ? WHERE id = ?",
                request.winnerTeamId(), decision, request.matchId()
        );

        UUID otherTeamId = request.winnerTeamId().equals(match.teamAId())
                ? match.teamBId() : match.teamAId();
        UUID battingTeamId = decision.equals("BAT") ? request.winnerTeamId() : otherTeamId;
        UUID bowlingTeamId = decision.equals("BOWL") ? request.winnerTeamId() : otherTeamId;

        return new TossResponse(request.matchId(), request.winnerTeamId(), decision, battingTeamId, bowlingTeamId);
    }

    private record MatchState(UUID teamAId, UUID teamBId, String status) {}

    public record Request(
            @NotNull UUID matchId,
            @NotNull UUID winnerTeamId,
            @NotNull String decision
    ) {}

    public record TossResponse(
            UUID matchId,
            UUID tossWinnerTeamId,
            String decision,
            UUID battingTeamId,
            UUID bowlingTeamId
    ) {}
}
