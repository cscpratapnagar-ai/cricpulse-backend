package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LiveScoreBroadcastPublicationGuard {
    private static final String PRIVATE_TOPIC_PREFIX = "/topic/innings/";
    private static final String PUBLIC_TOPIC_PREFIX = "/topic/public/innings/";

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messagingTemplate;
    private final GetLiveScore getLiveScore;
    private final boolean relayEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public LiveScoreBroadcastPublicationGuard(JdbcTemplate jdbc,
                                              SimpMessagingTemplate messagingTemplate,
                                              GetLiveScore getLiveScore,
                                              @org.springframework.beans.factory.annotation.Value("${app.websocket.broker-relay.enabled:false}") boolean relayEnabled) {
        this.jdbc = jdbc;
        this.messagingTemplate = messagingTemplate;
        this.getLiveScore = getLiveScore;
        this.relayEnabled = relayEnabled;
    }

    LiveScoreBroadcastPublicationGuard(JdbcTemplate jdbc, SimpMessagingTemplate messagingTemplate, GetLiveScore getLiveScore) {
        this(jdbc, messagingTemplate, getLiveScore, false);
    }

    /**
     * Serializes publication per innings across every API replica. A delayed
     * AFTER_COMMIT callback cannot publish a snapshot older than one already
     * sent by another callback/replica. The cursor is committed only after both
     * broker sends succeed; a crash in the send/commit gap may still duplicate
     * a version, so consumers must continue to discard duplicate versions.
     */
    @Transactional
    public boolean publishIfNewer(LiveScoreCommittedEvent event) {
        jdbc.queryForObject(
                "SELECT id FROM innings WHERE id = ? FOR UPDATE",
                java.util.UUID.class,
                event.inningsId()
        );
        jdbc.update("""
                INSERT INTO broadcast_publication_cursors (innings_id, published_version)
                VALUES (?, -1)
                ON CONFLICT (innings_id) DO NOTHING
                """, event.inningsId());

        GetLiveScore.Score score = getLiveScore.execute(event.inningsId());
        Long lastPublished = jdbc.queryForObject(
                "SELECT published_version FROM broadcast_publication_cursors WHERE innings_id = ?",
                Long.class,
                event.inningsId()
        );
        if (lastPublished != null && score.eventVersion() <= lastPublished) {
            return false;
        }

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
        payload.put("eventId", event.eventId());
        payload.put("sequenceNo", event.sequenceNo());
        payload.put("eventVersion", score.eventVersion());
        payload.put("occurredAt", OffsetDateTime.now());

        String inningsId = event.inningsId().toString();
        String privateDestination = relayEnabled ? "/topic/innings." + inningsId : PRIVATE_TOPIC_PREFIX + inningsId;
        String publicDestination = relayEnabled ? "/topic/public.innings." + inningsId : PUBLIC_TOPIC_PREFIX + inningsId;
        messagingTemplate.convertAndSend(privateDestination, (Object) payload);
        messagingTemplate.convertAndSend(publicDestination, (Object) payload);
        jdbc.update("""
                UPDATE broadcast_publication_cursors
                SET published_version = ?, published_at = CURRENT_TIMESTAMP
                WHERE innings_id = ? AND published_version < ?
                """, score.eventVersion(), event.inningsId(), score.eventVersion());
        return true;
    }
}
