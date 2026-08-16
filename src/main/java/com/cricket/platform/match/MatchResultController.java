package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.UUID;

@RestController
@RequestMapping("/api/matches")
public class MatchResultController {
    private final MatchResultService matchResultService;
    private final ScoringAccess scoringAccess;

    public MatchResultController(MatchResultService matchResultService, ScoringAccess scoringAccess) {
        this.matchResultService = matchResultService;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/result")
    public MatchResultService.Result result(
            @PathVariable UUID matchId,
            Authentication authentication
    ) {
        scoringAccess.requireMatchManager(matchId, authentication);
        return matchResultService.execute(matchId);
    }
}
