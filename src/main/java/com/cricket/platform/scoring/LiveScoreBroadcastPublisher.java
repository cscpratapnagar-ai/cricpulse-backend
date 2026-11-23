package com.cricket.platform.scoring;

import org.springframework.context.ApplicationEventPublisher;
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
    private static final String PRIVATE_TOPIC_PREFIX = "/topic/innings/";
    private static final String PUBLIC_TOPIC_PREFIX = "/topic/public/innings/";

    private final ApplicationEventPublisher applicationEventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final GetLiveScore getLiveScore;

    public LiveScoreBroadcastPublisher(ApplicationEventPublisher applicationEventPublisher,
                                       SimpMessagingTemplate messagingTemplate,
                                       GetLiveScore getLiveScore) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.messagingTemplate = messagingTemplate;
        this.getLiveScore = getLiveScore;
    }

    public void publishAfterCommit(LiveScoreCommittedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(LiveScoreCommittedEvent event) {
        // Score and eventVersion are read from the same authoritative projection.
        // A second query here could observe a later commit and pair its version
        // with an earlier score payload, creating a false revision boundary.
        GetLiveScore.Score score = getLiveScore.execute(event.inningsId());
        Map<String, Object> payload = new LinkedHashMap<>();

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
        payload.put("eventVersion", score.eventVersion());
        payload.put("occurredAt", OffsetDateTime.now());

        String inningsId = event.inningsId().toString();
        messagingTemplate.convertAndSend(PRIVATE_TOPIC_PREFIX + inningsId, (Object) payload);
        messagingTemplate.convertAndSend(PUBLIC_TOPIC_PREFIX + inningsId, (Object) payload);
    }
}
