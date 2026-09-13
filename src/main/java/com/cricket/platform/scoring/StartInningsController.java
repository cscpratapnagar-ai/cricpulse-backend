package com.cricket.platform.scoring;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/scoring/innings")
public class StartInningsController {
    private final StartInnings startInnings;
    private final ScoringAccess scoringAccess;

    public StartInningsController(StartInnings startInnings, ScoringAccess scoringAccess) {
        this.startInnings = startInnings;
        this.scoringAccess = scoringAccess;
    }

    @PostMapping
    public ResponseEntity<StartInnings.InningsResponse> start(
            @Valid @RequestBody StartInnings.Request request,
            Authentication authentication) {
        scoringAccess.requireMatchManager(request.matchId(), authentication);
        return ResponseEntity.ok(startInnings.execute(request));
    }
}
