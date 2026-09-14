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
public class MatchAnalyticsController {
    private final GetMatchAnalytics analytics;
    private final ScoringAccess scoringAccess;

    public MatchAnalyticsController(GetMatchAnalytics analytics, ScoringAccess scoringAccess) {
        this.analytics = analytics;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/analytics")
    public GetMatchAnalytics.MatchAnalytics get(@PathVariable UUID matchId, Authentication authentication) {
        scoringAccess.requireMatchManager(matchId, authentication);
        return analytics.get(matchId);
    }
}
