package com.cricket.platform.scoring;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {
    private final StartInnings startInnings;
    private final RecordDelivery recordDelivery;
    private final GetLiveScore getLiveScore;
    private final UndoDelivery undoDelivery;
    private final SimpMessagingTemplate messaging;

    public ScoringController(StartInnings startInnings, RecordDelivery recordDelivery, GetLiveScore getLiveScore,
                             SimpMessagingTemplate messaging, UndoDelivery undoDelivery) {
        this.startInnings = startInnings;
        this.recordDelivery = recordDelivery;
        this.getLiveScore = getLiveScore;
        this.messaging = messaging;
        this.undoDelivery = undoDelivery;
    }

    @PostMapping("/innings")
    StartInnings.InningsResponse start(@Valid @RequestBody StartInnings.Request request) {
        return startInnings.execute(request);
    }

    @PostMapping("/innings/{inningsId}/deliveries")
    RecordDelivery.DeliveryResponse delivery(@PathVariable UUID inningsId,
                                               @Valid @RequestBody RecordDelivery.Request request) {
        if (!inningsId.equals(request.inningsId())) throw new IllegalArgumentException("Innings ID does not match URL");
        RecordDelivery.DeliveryResponse response = recordDelivery.execute(request);
        GetLiveScore.Score score = getLiveScore.execute(inningsId);
        messaging.convertAndSend("/topic/innings/" + inningsId, score);
        return response;
    }

    @GetMapping("/innings/{inningsId}")
    GetLiveScore.Score live(@PathVariable UUID inningsId) { return getLiveScore.execute(inningsId); }

    @PostMapping("/innings/{inningsId}/undo")
    GetLiveScore.Score undo(@PathVariable UUID inningsId) {
        GetLiveScore.Score score = undoDelivery.execute(inningsId);
        messaging.convertAndSend("/topic/innings/" + inningsId, score);
        return score;
    }
}
