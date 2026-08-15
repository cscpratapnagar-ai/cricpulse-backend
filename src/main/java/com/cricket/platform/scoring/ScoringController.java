package com.cricket.platform.scoring;

import jakarta.validation.Valid;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {
    private final StartInnings startInnings;
    private final RecordDelivery recordDelivery;
    private final GetLiveScore getLiveScore;
    private final UndoDelivery undoDelivery;
    private final InningsLifecycle inningsLifecycle;
    private final SimpMessagingTemplate messaging;
    private final ScoringAccess scoringAccess;

    public ScoringController(StartInnings startInnings,
                             RecordDelivery recordDelivery,
                             GetLiveScore getLiveScore,
                             UndoDelivery undoDelivery,
                             InningsLifecycle inningsLifecycle,
                             SimpMessagingTemplate messaging,
                             ScoringAccess scoringAccess) {
        this.startInnings = startInnings;
        this.recordDelivery = recordDelivery;
        this.getLiveScore = getLiveScore;
        this.undoDelivery = undoDelivery;
        this.inningsLifecycle = inningsLifecycle;
        this.messaging = messaging;
        this.scoringAccess = scoringAccess;
    }

    @PostMapping("/innings")
    StartInnings.InningsResponse start(@Valid @RequestBody StartInnings.Request request,
                                       Authentication authentication) {
        scoringAccess.requireMatchManager(request.matchId(), authentication);
        return startInnings.execute(request);
    }

    @PostMapping("/innings/{inningsId}/deliveries")
    RecordDelivery.DeliveryResponse delivery(@PathVariable UUID inningsId,
                                               @Valid @RequestBody RecordDelivery.Request request,
                                               Authentication authentication) {
        if (!inningsId.equals(request.inningsId())) {
            throw new IllegalArgumentException("Innings ID does not match URL");
        }
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);

        RecordDelivery.DeliveryResponse response = recordDelivery.execute(request);
        inningsLifecycle.evaluate(inningsId);

        GetLiveScore.Score score = getLiveScore.execute(inningsId);
        messaging.convertAndSend("/topic/innings/" + inningsId, score);
        return response;
    }

    @GetMapping("/innings/{inningsId}")
    GetLiveScore.Score live(@PathVariable UUID inningsId,
                            Authentication authentication) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);
        return getLiveScore.execute(inningsId);
    }

    @PostMapping("/innings/{inningsId}/undo")
    GetLiveScore.Score undo(@PathVariable UUID inningsId,
                            Authentication authentication) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);
        GetLiveScore.Score score = undoDelivery.execute(inningsId);
        messaging.convertAndSend("/topic/innings/" + inningsId, score);
        return score;
    }
}
