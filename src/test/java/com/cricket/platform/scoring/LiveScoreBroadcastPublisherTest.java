package com.cricket.platform.scoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class LiveScoreBroadcastPublisherTest {

    @Test
    void publishesCommittedScoreToInningsTopicWithVersionMetadata() {
        ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        SimpMessagingTemplate messaging = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        GetLiveScore liveScore = org.mockito.Mockito.mock(GetLiveScore.class);
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);

        UUID inningsId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID striker = UUID.randomUUID();
        UUID nonStriker = UUID.randomUUID();
        UUID bowler = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        GetLiveScore.Score score = new GetLiveScore.Score(
                inningsId, matchId, 1, 42, 1, 30, 20, "LIVE", 0,
                3, 2, striker, nonStriker, bowler,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        when(liveScore.execute(inningsId)).thenReturn(score);
        when(jdbc.queryForObject("SELECT state_version FROM innings WHERE id = ?", Long.class, inningsId))
                .thenReturn(7L);

        LiveScoreBroadcastPublisher publisher =
                new LiveScoreBroadcastPublisher(events, messaging, liveScore, jdbc);

        publisher.onCommitted(new LiveScoreCommittedEvent(
                inningsId, eventId, 9L, 6, "DELIVERY_RECORDED"));

        verify(messaging).convertAndSend(eq("/topic/innings/" + inningsId), any(Object.class));
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/innings/" + inningsId), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(eventId, payload.get("eventId"));
        org.junit.jupiter.api.Assertions.assertEquals(9L, payload.get("sequenceNo"));
        org.junit.jupiter.api.Assertions.assertEquals(7L, payload.get("eventVersion"));
        org.junit.jupiter.api.Assertions.assertEquals("DELIVERY_RECORDED", payload.get("eventType"));
        org.junit.jupiter.api.Assertions.assertEquals(matchId, payload.get("matchId"));
        org.junit.jupiter.api.Assertions.assertEquals(42, payload.get("runs"));
    }
}
