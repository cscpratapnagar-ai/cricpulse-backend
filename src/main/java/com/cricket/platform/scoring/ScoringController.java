package com.cricket.platform.scoring;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cricket.platform.scoring.api.DeliveryRequest;
import com.cricket.platform.scoring.api.LiveScoreResponse;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {

    private final EventFirstProjectionService eventFirstProjectionService;
    private final InningsLifecycle inningsLifecycle;
    private final MatchResultService matchResultService;
    private final LiveScoreBroadcastPublisher liveScoreBroadcastPublisher;
    private final ScoringAccess scoringAccess;
    private final GetLiveScore getLiveScore;

    public ScoringController(
            EventFirstProjectionService eventFirstProjectionService,
            InningsLifecycle inningsLifecycle,
            MatchResultService matchResultService,
            LiveScoreBroadcastPublisher liveScoreBroadcastPublisher,
            ScoringAccess scoringAccess,
            GetLiveScore getLiveScore) {
        this.eventFirstProjectionService = eventFirstProjectionService;
        this.inningsLifecycle = inningsLifecycle;
        this.matchResultService = matchResultService;
        this.liveScoreBroadcastPublisher = liveScoreBroadcastPublisher;
        this.scoringAccess = scoringAccess;
        this.getLiveScore = getLiveScore;
    }

    @PostMapping("/innings/{inningsId}/deliveries")
    @Transactional
    public ResponseEntity<LiveScoreResponse> recordDelivery(
            @PathVariable UUID inningsId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody DeliveryRequest request) {
        UUID commandId = parseCommandId(commandIdHeader);
        ScoringAccess.InningsState state = scoringAccess.lockInnings(inningsId);
        ScoringAccess.DeliveryPosition position = scoringAccess.nextDeliveryPosition(inningsId);
        UUID strikerId = request.strikerId();
        UUID nonStrikerId = request.nonStrikerId();
        UUID bowlerId = request.bowlerId();

        if (commandId != null && scoringAccess.deliveryCommandExists(commandId)) {
            return ResponseEntity.ok(getLiveScore.execute(inningsId));
        }

        DeliveryCommand command = new DeliveryCommand(
                commandId,
                inningsId,
                strikerId,
                nonStrikerId,
                bowlerId,
                request.batRuns(),
                request.extraRuns(),
                request.extraType(),
                request.wicketType(),
                request.dismissedPlayerId(),
                request.newBatterId(),
                null
        );

        EventFirstProjectionService.Result projection = eventFirstProjectionService.record(
                command, position.overNumber(), position.ballNumber());
        if (!projection.created()) {
            return ResponseEntity.ok(getLiveScore.execute(inningsId));
        }

        DeliveryEvent event = projection.event();
        InningsLifecycle.Completion completion = inningsLifecycle.evaluate(inningsId);

        String eventType = "DELIVERY_RECORDED";
        if (completion.completed()) {
            if (state.inningsNumber() == 2) {
                matchResultService.execute(scoringAccess.matchIdForInnings(inningsId));
                eventType = "MATCH_RESULT";
            } else {
                eventType = "INNINGS_COMPLETED";
            }
        }

        liveScoreBroadcastPublisher.publishAfterCommit(new LiveScoreCommittedEvent(
                event.inningsId(),
                event.eventId(),
                event.sequenceNo(),
                event.eventVersion(),
                eventType
        ));

        return ResponseEntity.ok(getLiveScore.execute(inningsId));
    }

    @GetMapping("/innings/{inningsId}")
    public ResponseEntity<LiveScoreResponse> getLiveScore(@PathVariable UUID inningsId) {
        return ResponseEntity.ok(getLiveScore.execute(inningsId));
    }

    private UUID parseCommandId(String commandIdHeader) {
        if (commandIdHeader == null || commandIdHeader.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(commandIdHeader.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("X-Command-Id must be a valid UUID", ex);
        }
    }
}
