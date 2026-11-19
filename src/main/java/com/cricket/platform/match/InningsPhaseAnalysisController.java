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
public class InningsPhaseAnalysisController {
    private final GetInningsPhaseAnalysis analysis;
    private final ScoringAccess scoringAccess;

    public InningsPhaseAnalysisController(
            GetInningsPhaseAnalysis analysis,
            ScoringAccess scoringAccess
    ) {
        this.analysis = analysis;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/{matchId}/phase-analysis")
    public List<GetInningsPhaseAnalysis.PhaseSummary> get(
            @PathVariable UUID matchId,
            Authentication authentication
    ) {
        scoringAccess.requireMatchManager(matchId, authentication);
        return analysis.get(matchId);
    }
}
