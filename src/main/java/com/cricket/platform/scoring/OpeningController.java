package com.cricket.platform.scoring;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/scoring/innings")
public class OpeningController {
    private final SetupInningsOpening setupInningsOpening;
    private final ScoringAccess scoringAccess;

    public OpeningController(SetupInningsOpening setupInningsOpening, ScoringAccess scoringAccess) {
        this.setupInningsOpening = setupInningsOpening;
        this.scoringAccess = scoringAccess;
    }

    @PostMapping("/{inningsId}/opening")
    public ResponseEntity<SetupInningsOpening.OpeningResponse> setup(
            @PathVariable UUID inningsId,
            @Valid @RequestBody OpeningRequest request,
            Authentication authentication
    ) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);
        return ResponseEntity.ok(setupInningsOpening.execute(
                new SetupInningsOpening.Request(
                        inningsId,
                        request.strikerId(),
                        request.nonStrikerId(),
                        request.bowlerId()
                )
        ));
    }

    public record OpeningRequest(
            UUID strikerId,
            UUID nonStrikerId,
            UUID bowlerId
    ) {}
}
