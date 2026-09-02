package com.cricket.platform.scoring;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class LiveScoreBroadcastPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final GetLiveScore getLiveScore;
    private final JdbcTemplate jdbc;

    public LiveScoreBroadcastPublisher(ApplicationEventPublisher applicationEventPublisher,
                                       SimpMessagingTemplate messagingTemplate,
                                       GetLiveScore getLiveScore,
                                       JdbcTemplate jdbc) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.messagingTemplate = messagingTemplate;
        this.getLiveScore = getLiveScore;
        this.jdbc = jdbc;
    }

    public void publishAfterCommit(LiveScoreCommittedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(LiveScoreCommittedEvent event) {
        GetLiveScore.Score score = getLiveScore.execute(event.inningsId());
        Long stateVersion = jdbc.queryForObject(
                "SELECT state_version FROM innings WHERE id = ?",
                Long.class,
                event.inningsId()
        );
        Map<String, Object> payload = new LinkedHashMap<>();

        // Keep the realtime contract flat so existing Angular consumers can
        // continue treating the event body as a LiveScore object.
        payload.put("inningsId", score.inningsId());
        payload.put("matchId", score.matchId());
        payload.put("inningsNumber", score.inningsNumber());
        payload.put("runs", score.runs());
        payload.put("wickets", score.wickets());
        payload.put("legalBalls", score.legalBalls());
        payload.put("totalOvers", score.totalOvers());
        payload.put("status", score.status());
        payload.put("targetRuns", score.targetRuns());
        payload.put("currentOver", score.currentOver());
        payload.put("currentBall", score.currentBall());
        payload.put("strikerId", score.strikerId());
        payload.put("nonStrikerId", score.nonStrikerId());
        payload.put("currentBowlerId", score.currentBowlerId());
        payload.put("batters", score.batters());
        payload.put("bowlers", score.bowlers());
        payload.put("overs", score.overs());
        payload.put("recentBalls", score.recentBalls());
        payload.put("partnership", score.partnership());
        payload.put("fallOfWickets", score.fallOfWickets());
        payload.put("eventType", event.eventType());
        payload.put("eventId", event.eventId() != null ? event.eventId() : UUID.randomUUID());
        payload.put("sequenceNo", event.sequenceNo());
        payload.put("eventVersion", stateVersion != null ? stateVersion : (long) event.eventVersion());
        payload.put("occurredAt", OffsetDateTime.now());

        messagingTemplate.convertAndSend(
                "/topic/innings/" + event.inningsId(),
                (Object) payload
        );
    }
}
