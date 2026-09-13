package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/matches")
public class MatchIntelligenceController {
    private final GetMatchIntelligence intelligence;
    private final ScoringAccess scoringAccess;

    public MatchIntelligenceController(GetMatchIntelligence intelligence, ScoringAccess scoringAccess) {
        this.intelligence = intelligence;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/intelligence")
    public GetMatchIntelligence.MatchIntelligence get(@PathVariable UUID matchId, Authentication authentication) {
        scoringAccess.requireMatchManager(matchId, authentication);
        return intelligence.get(matchId);
    }
}
