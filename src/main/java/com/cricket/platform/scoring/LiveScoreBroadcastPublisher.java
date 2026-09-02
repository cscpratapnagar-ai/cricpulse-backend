package com.cricket.platform.scoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LiveScoreBroadcastPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final GetLiveScore getLiveScore;
    private final ObjectMapper objectMapper;

    public LiveScoreBroadcastPublisher(ApplicationEventPublisher applicationEventPublisher,
                                       SimpMessagingTemplate messagingTemplate,
                                       GetLiveScore getLiveScore,
                                       ObjectMapper objectMapper) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.messagingTemplate = messagingTemplate;
        this.getLiveScore = getLiveScore;
        this.objectMapper = objectMapper;
    }

    public void publishAfterCommit(LiveScoreCommittedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(LiveScoreCommittedEvent event) {
        GetLiveScore.Score score = getLiveScore.execute(event.inningsId());
        Map<String, Object> payload = new LinkedHashMap<>(
                objectMapper.convertValue(score, new TypeReference<Map<String, Object>>() {})
        );
        payload.put("eventType", "DELIVERY_RECORDED");
        payload.put("eventId", event.eventId());
        payload.put("sequenceNo", event.sequenceNo());
        payload.put("eventVersion", event.eventVersion());
        payload.put("occurredAt", OffsetDateTime.now());

        messagingTemplate.convertAndSend(
                "/topic/innings/" + event.inningsId(),
                payload
        );
    }
}
