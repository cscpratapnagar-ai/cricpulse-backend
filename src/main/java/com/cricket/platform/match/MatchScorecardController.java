package com.cricket.platform.match;

import com.cricket.platform.scoring.GetScorecard;
import com.cricket.platform.scoring.ScoringAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/matches")
public class MatchScorecardController {
    private final JdbcTemplate jdbc;
    private final GetScorecard getScorecard;
    private final ScoringAccess scoringAccess;

    public MatchScorecardController(JdbcTemplate jdbc, GetScorecard getScorecard, ScoringAccess scoringAccess) {
        this.jdbc = jdbc;
        this.getScorecard = getScorecard;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/scorecard")
    public List<GetScorecard.Scorecard> scorecard(
            @PathVariable UUID matchId,
            Authentication authentication
    ) {
        scoringAccess.requireMatchManager(matchId, authentication);
        List<UUID> inningsIds = jdbc.queryForList(
                "SELECT id FROM innings WHERE match_id = ? ORDER BY innings_number",
                UUID.class,
                matchId
        );
        return inningsIds.stream().map(getScorecard::execute).toList();
    }
}
