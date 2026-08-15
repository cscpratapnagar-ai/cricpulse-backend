package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/matches")
public class CurrentInningsController {
    private final JdbcTemplate jdbc;
    private final ScoringAccess scoringAccess;

    public CurrentInningsController(JdbcTemplate jdbc, ScoringAccess scoringAccess) {
        this.jdbc = jdbc;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/current-innings")
    public CurrentInningsResponse current(
            @PathVariable UUID matchId,
            Authentication authentication,
            HttpServletResponse response
    ) {
        scoringAccess.requireMatchManager(matchId, authentication);

        CurrentInningsResponse innings = jdbc.query(
                """
                SELECT i.id,
                       i.innings_number,
                       i.batting_team_id,
                       i.bowling_team_id,
                       i.total_runs,
                       i.wickets,
                       i.legal_balls,
                       i.status,
                       i.striker_id,
                       i.non_striker_id,
                       i.current_bowler_id
                FROM innings i
                JOIN matches m ON m.id = i.match_id
                WHERE i.match_id = ?
                  AND (
                      i.id = m.current_innings_id
                      OR (m.current_innings_id IS NULL AND i.status = 'LIVE')
                  )
                ORDER BY i.innings_number DESC
                LIMIT 1
                """,
                (rs, row) -> new CurrentInningsResponse(
                        rs.getObject("id", UUID.class),
                        rs.getInt("innings_number"),
                        rs.getObject("batting_team_id", UUID.class),
                        rs.getObject("bowling_team_id", UUID.class),
                        rs.getInt("total_runs"),
                        rs.getInt("wickets"),
                        rs.getInt("legal_balls"),
                        rs.getString("status"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getObject("current_bowler_id", UUID.class)
                ),
                matchId
        ).stream().findFirst().orElse(null);

        if (innings == null || !"LIVE".equals(innings.status())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        return innings;
    }

    public record CurrentInningsResponse(
            UUID inningsId,
            int inningsNumber,
            UUID battingTeamId,
            UUID bowlingTeamId,
            int runs,
            int wickets,
            int legalBalls,
            String status,
            UUID strikerId,
            UUID nonStrikerId,
            UUID currentBowlerId
    ) {}
}
