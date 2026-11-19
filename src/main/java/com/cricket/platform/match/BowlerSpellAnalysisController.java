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
public class BowlerSpellAnalysisController {
    private final GetBowlerSpellAnalysis analysis;
    private final ScoringAccess scoringAccess;

    public BowlerSpellAnalysisController(GetBowlerSpellAnalysis analysis, ScoringAccess scoringAccess) {
        this.analysis = analysis;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/bowling-spells")
    public List<GetBowlerSpellAnalysis.BowlerSpell> get(
            @PathVariable UUID matchId,
            Authentication authentication
    ) {
        scoringAccess.requireMatchManager(matchId, authentication);
        return analysis.get(matchId);
    }
}
