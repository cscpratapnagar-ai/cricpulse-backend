package com.cricket.platform.scoring;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public ScoringController(StartInnings startInnings,
                             RecordDelivery recordDelivery,
                             GetLiveScore getLiveScore,
                             UndoDelivery undoDelivery,
                             InningsLifecycle inningsLifecycle,
                             SimpMessagingTemplate messaging,
                             ScoringAccess scoringAccess,
                             JdbcTemplate jdbc) {
        this.startInnings = startInnings;
        this.recordDelivery = recordDelivery;
        this.getLiveScore = getLiveScore;
        this.undoDelivery = undoDelivery;
        this.inningsLifecycle = inningsLifecycle;
        this.messaging = messaging;
        this.scoringAccess = scoringAccess;
        this.jdbc = jdbc;
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

        BallPosition position = jdbc.queryForObject(
                "SELECT legal_balls FROM innings WHERE id = ? FOR UPDATE",
                (rs, row) -> {
                    int legalBalls = rs.getInt("legal_balls");
                    return new BallPosition(legalBalls / 6, (legalBalls % 6) + 1);
                },
                inningsId
        );
        if (position == null) {
            throw new IllegalArgumentException("Innings was not found");
        }

        // Backend is the single source of truth for over/ball labels. This prevents
        // stale UI state after reconnects, undo, wides/no-balls and over completion.
        RecordDelivery.Request normalized = new RecordDelivery.Request(
                request.inningsId(), position.overNumber(), position.ballNumber(),
                request.strikerId(), request.nonStrikerId(), request.bowlerId(),
                request.batRuns(), request.extraRuns(), request.extraType(),
                request.wicketType(), request.dismissedPlayerId(), request.newBatterId()
        );

        RecordDelivery.DeliveryResponse response = recordDelivery.execute(normalized);
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

    private record BallPosition(int overNumber, int ballNumber) {}
}
