package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.cricket.platform.match.MatchResultService;
import com.cricket.platform.match.PlayingXiController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {
    private final StartInnings startInnings;
    private final EventFirstProjectionService eventFirstProjectionService;
    private final GetLiveScore getLiveScore;
    private final UndoDelivery undoDelivery;
    private final InningsLifecycle inningsLifecycle;
    private final LiveScoreBroadcastPublisher liveScoreBroadcastPublisher;
    private final ScoringAccess scoringAccess;
    private final JdbcTemplate jdbc;
    private final MatchResultService matchResultService;

    public ScoringController(StartInnings startInnings,
                             EventFirstProjectionService eventFirstProjectionService,
                             GetLiveScore getLiveScore,
                             UndoDelivery undoDelivery,
                             InningsLifecycle inningsLifecycle,
                             LiveScoreBroadcastPublisher liveScoreBroadcastPublisher,
                             ScoringAccess scoringAccess,
                             JdbcTemplate jdbc,
                             MatchResultService matchResultService) {
        this.startInnings = startInnings;
        this.eventFirstProjectionService = eventFirstProjectionService;
        this.getLiveScore = getLiveScore;
        this.undoDelivery = undoDelivery;
        this.inningsLifecycle = inningsLifecycle;
        this.liveScoreBroadcastPublisher = liveScoreBroadcastPublisher;
        this.scoringAccess = scoringAccess;
        this.jdbc = jdbc;
        this.matchResultService = matchResultService;
    }

    @PostMapping("/innings")
    StartInnings.InningsResponse start(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid StartInnings.Request request,
                                       Authentication authentication) {
        scoringAccess.requireMatchManager(request.matchId(), authentication);
        return startInnings.execute(request);
    }

    @PostMapping("/innings/{inningsId}/deliveries")
    @Transactional
    GetLiveScore.Score delivery(@PathVariable UUID inningsId,
                                @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
                                @RequestBody RecordDelivery.Request request,
                                Authentication authentication) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);

        DeliveryState state = jdbc.queryForObject(
                """
                SELECT innings_number, legal_balls, striker_id, non_striker_id, current_bowler_id, status
                FROM innings
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new DeliveryState(
                        rs.getInt("innings_number"),
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
        UUID commandId = parseCommandId(commandIdHeader);

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

        eventFirstProjectionService.record(command, position.overNumber(), position.ballNumber());
        InningsLifecycle.Completion completion = inningsLifecycle.evaluate(inningsId);

        if (completion.completed() && state.inningsNumber() == 2) {
            matchResultService.execute(scoringAccess.matchIdForInnings(inningsId));
        }

        return getLiveScore.execute(inningsId);
    }

    @GetMapping("/innings/{inningsId}")
    GetLiveScore.Score live(@PathVariable UUID inningsId,
                            Authentication authentication) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);
        return getLiveScore.execute(inningsId);
    }

    /**
     * Compatibility endpoint for scoring E2E clients that resolve the Playing XI
     * from an innings id rather than a match id.
     */
    @GetMapping("/innings/{inningsId}/playing-xi")
    List<PlayingXiController.PlayingPlayer> playingXi(@PathVariable UUID inningsId,
                                                       Authentication authentication) {
        UUID matchId = scoringAccess.matchIdForInnings(inningsId);
        scoringAccess.requireMatchManager(matchId, authentication);
        return jdbc.query("""
                SELECT mp.team_id, mp.player_id, u.full_name, mp.is_captain, mp.is_vice_captain, mp.is_wicket_keeper
                FROM match_players mp
                JOIN players p ON p.id = mp.player_id
                JOIN users u ON u.id = p.user_id
                WHERE mp.match_id = ? AND mp.is_playing_xi = TRUE
                ORDER BY mp.team_id, u.full_name
                """,
                (rs, row) -> new PlayingXiController.PlayingPlayer(
                        rs.getObject("team_id", UUID.class),
                        rs.getObject("player_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getBoolean("is_captain"),
                        rs.getBoolean("is_vice_captain"),
                        rs.getBoolean("is_wicket_keeper")
                ),
                matchId
        );
    }

    @PostMapping("/innings/{inningsId}/undo")
    @Transactional
    GetLiveScore.Score undo(@PathVariable UUID inningsId,
                            Authentication authentication) {
        scoringAccess.requireMatchManager(scoringAccess.matchIdForInnings(inningsId), authentication);
        GetLiveScore.Score score = undoDelivery.execute(inningsId);
        jdbc.update("UPDATE innings SET state_version = state_version + 1 WHERE id = ?", inningsId);
        liveScoreBroadcastPublisher.publishAfterCommit(new LiveScoreCommittedEvent(
                inningsId,
                UUID.randomUUID(),
                0L,
                0,
                "DELIVERY_UNDONE"
        ));
        return score;
    }

    private UUID parseCommandId(String header) {
        if (header == null || header.isBlank()) return UUID.randomUUID();
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("X-Command-Id must be a valid UUID");
        }
    }

    private record DeliveryState(
            int inningsNumber,
            int legalBalls,
            UUID strikerId,
            UUID nonStrikerId,
            UUID currentBowlerId,
            String status
    ) {}

    private record BallPosition(int overNumber, int ballNumber) {}
}
