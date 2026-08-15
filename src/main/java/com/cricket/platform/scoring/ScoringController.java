package com.cricket.platform.scoring;

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
    StartInnings.InningsResponse start(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid StartInnings.Request request,
                                       Authentication authentication) {
        scoringAccess.requireMatchManager(request.matchId(), authentication);
        return startInnings.execute(request);
    }

    @PostMapping("/innings/{inningsId}/deliveries")
    GetLiveScore.Score delivery(@PathVariable UUID inningsId,
                                @RequestBody RecordDelivery.Request request,
                                Authentication authentication) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);

        DeliveryState state = jdbc.queryForObject(
                """
                SELECT legal_balls, striker_id, non_striker_id, current_bowler_id, status
                FROM innings
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new DeliveryState(
                        rs.getInt("legal_balls"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getObject("current_bowler_id", UUID.class),
                        rs.getString("status")
                ),
                inningsId
        );

        if (state == null) {
            throw new IllegalArgumentException("Innings was not found");
        }
        if (!"LIVE".equals(state.status())) {
            throw new IllegalArgumentException("Innings is not live");
        }

        // The database is the source of truth. UI requests may omit the current
        // player context, so restore it from the locked innings row.
        UUID requestInningsId = request.inningsId() != null ? request.inningsId() : inningsId;
        if (!inningsId.equals(requestInningsId)) {
            throw new IllegalArgumentException("Innings ID does not match URL");
        }

        UUID strikerId = request.strikerId() != null ? request.strikerId() : state.strikerId();
        UUID nonStrikerId = request.nonStrikerId() != null ? request.nonStrikerId() : state.nonStrikerId();
        UUID bowlerId = request.bowlerId() != null ? request.bowlerId() : state.currentBowlerId();

        if (strikerId == null || nonStrikerId == null || bowlerId == null) {
            throw new IllegalArgumentException("Current striker, non-striker and bowler must be set before recording a delivery");
        }

        BallPosition position = new BallPosition(state.legalBalls() / 6, (state.legalBalls() % 6) + 1);

        RecordDelivery.Request normalized = new RecordDelivery.Request(
                inningsId,
                position.overNumber(),
                position.ballNumber(),
                strikerId,
                nonStrikerId,
                bowlerId,
                request.batRuns(),
                request.extraRuns(),
                request.extraType(),
                request.wicketType(),
                request.dismissedPlayerId(),
                request.newBatterId()
        );

        recordDelivery.execute(normalized);
        inningsLifecycle.evaluate(inningsId);

        // Always return the complete persisted score, not the small delivery
        // acknowledgement. This keeps the scorer UI, resume state and WebSocket
        // payload on exactly the same contract.
        GetLiveScore.Score score = getLiveScore.execute(inningsId);
        messaging.convertAndSend("/topic/innings/" + inningsId, score);
        return score;
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

    private record DeliveryState(
            int legalBalls,
            UUID strikerId,
            UUID nonStrikerId,
            UUID currentBowlerId,
            String status
    ) {}

    private record BallPosition(int overNumber, int ballNumber) {}
}
