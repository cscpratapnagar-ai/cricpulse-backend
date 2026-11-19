package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/matches")
public class MatchupIntelligenceController {
    private final GetMatchupIntelligence matchups;
    private final ScoringAccess scoringAccess;

    public MatchupIntelligenceController(
            GetMatchupIntelligence matchups,
            ScoringAccess scoringAccess
    ) {
        this.matchups = matchups;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/matchups")
    public List<GetMatchupIntelligence.Matchup> get(
            @PathVariable UUID matchId,
            Authentication authentication
    ) {
        scoringAccess.requireMatchManager(matchId, authentication);
        return matchups.get(matchId);
    }
}
