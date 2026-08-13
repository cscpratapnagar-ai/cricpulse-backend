package com.cricket.platform.scoring;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ScoringWebSocketPublisher {
    private final SimpMessagingTemplate messagingTemplate;

    public ScoringWebSocketPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(UUID matchId, ScoringEngine.Result result) {
        if (matchId == null || result == null) {
            throw new IllegalArgumentException("Match id and scoring result are required");
        }
        messagingTemplate.convertAndSend("/topic/matches/" + matchId + "/score", result);
    }
}
