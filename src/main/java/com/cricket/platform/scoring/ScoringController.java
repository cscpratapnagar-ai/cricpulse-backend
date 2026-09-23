package com.cricket.platform.scoring;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cricket.platform.match.MatchResultService;

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
    public ResponseEntity<GetLiveScore.Score> recordDelivery(
            @PathVariable UUID inningsId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody RecordDelivery.Request request,
            Authentication authentication) {
        UUID commandId = parseCommandId(commandIdHeader);
        UUID matchId = scoringAccess.matchIdForInnings(inningsId);
        scoringAccess.requireMatchManager(matchId, authentication);

        if (!inningsId.equals(request.inningsId())) {
            throw new IllegalArgumentException("Path inningsId must match the request inningsId");
        }

        DeliveryCommand command = new DeliveryCommand(
                commandId,
                request.inningsId(),
                request.strikerId(),
                request.nonStrikerId(),
                request.bowlerId(),
                request.batRuns(),
                request.extraRuns(),
                request.extraType(),
                request.wicketType(),
                request.dismissedPlayerId(),
                request.newBatterId(),
                null
        );

        EventFirstProjectionService.Result projection = eventFirstProjectionService.record(
                command,
                request.overNumber(),
                request.ballNumber());

        if (!projection.created()) {
            return ResponseEntity.ok(getLiveScore.execute(inningsId));
        }

        DeliveryEvent event = projection.event();
        InningsLifecycle.Completion completion = inningsLifecycle.evaluate(inningsId);

        String eventType = "DELIVERY_RECORDED";
        if (completion.completed()) {
            if (event != null && isSecondInnings(inningsId)) {
                matchResultService.execute(matchId);
                eventType = "MATCH_RESULT";
            } else {
                eventType = "INNINGS_COMPLETED";
            }
        }

        if (event != null) {
            liveScoreBroadcastPublisher.publishAfterCommit(new LiveScoreCommittedEvent(
                    event.inningsId(),
                    event.eventId(),
                    event.sequenceNo(),
                    event.eventVersion(),
                    eventType
            ));
        }

        return ResponseEntity.ok(getLiveScore.execute(inningsId));
    }

    @GetMapping("/innings/{inningsId}")
    public ResponseEntity<GetLiveScore.Score> getLiveScore(
            @PathVariable UUID inningsId,
            Authentication authentication) {
        UUID matchId = scoringAccess.matchIdForInnings(inningsId);
        scoringAccess.requireMatchAccess(matchId, authentication);
        return ResponseEntity.ok(getLiveScore.execute(inningsId));
    }

    private boolean isSecondInnings(UUID inningsId) {
        return getLiveScore.execute(inningsId).inningsNumber() == 2;
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
