package com.cricket.platform.scoring;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scoring/audit")
public class ScoringAuditController {
    private final ScoringAuditService auditService;
    private final ScoringAccess scoringAccess;

    public ScoringAuditController(ScoringAuditService auditService, ScoringAccess scoringAccess) {
        this.auditService = auditService;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/innings/{inningsId}")
    public ResponseEntity<ScoringAuditService.AuditSnapshot> snapshot(
            @PathVariable UUID inningsId,
            Authentication authentication) {
        UUID matchId = scoringAccess.matchIdForInnings(inningsId);
        scoringAccess.requireMatchManager(matchId, authentication);
        return ResponseEntity.ok(auditService.snapshot(inningsId));
    }
}
