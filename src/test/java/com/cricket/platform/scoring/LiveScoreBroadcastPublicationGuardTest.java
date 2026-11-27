package com.cricket.platform.scoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class LiveScoreBroadcastPublicationGuardTest {

    @Test
    void publishesNewestSnapshotToBothTopicsAndAdvancesCursor() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpMessagingTemplate messaging = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        GetLiveScore liveScore = org.mockito.Mockito.mock(GetLiveScore.class);
        UUID inningsId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        GetLiveScore.Score score = new GetLiveScore.Score(
                inningsId, matchId, 1, 18, 1, 12, 20, "LIVE", 0,
                2, 0, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(), List.of(), List.of(), List.of(), null, List.of()
        ).withEventVersion(12L);
        when(jdbc.queryForObject(eq("SELECT id FROM innings WHERE id = ? FOR UPDATE"), eq(UUID.class), eq(inningsId)))
                .thenReturn(inningsId);
        when(jdbc.queryForObject(eq("SELECT published_version FROM broadcast_publication_cursors WHERE innings_id = ?"), eq(Long.class), eq(inningsId)))
                .thenReturn(11L);
        when(liveScore.execute(inningsId)).thenReturn(score);

        LiveScoreBroadcastPublicationGuard guard = new LiveScoreBroadcastPublicationGuard(jdbc, messaging, liveScore);
        assertTrue(guard.publishIfNewer(new LiveScoreCommittedEvent(inningsId, UUID.randomUUID(), 12, 12, "DELIVERY_RECORDED")));

        verify(messaging).convertAndSend(eq("/topic/innings/" + inningsId), any(Object.class));
        verify(messaging).convertAndSend(eq("/topic/public/innings/" + inningsId), any(Object.class));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("UPDATE broadcast_publication_cursors"),
                eq(12L), eq(inningsId), eq(12L));
    }

    @Test
    void skipsDelayedCallbackWhenItsSnapshotVersionWasAlreadyPublished() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpMessagingTemplate messaging = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        GetLiveScore liveScore = org.mockito.Mockito.mock(GetLiveScore.class);
        UUID inningsId = UUID.randomUUID();
        GetLiveScore.Score score = new GetLiveScore.Score(
                inningsId, UUID.randomUUID(), 1, 25, 1, 18, 20, "LIVE", 0,
                3, 0, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(), List.of(), List.of(), List.of(), null, List.of()
        ).withEventVersion(18L);
        when(jdbc.queryForObject(eq("SELECT id FROM innings WHERE id = ? FOR UPDATE"), eq(UUID.class), eq(inningsId)))
                .thenReturn(inningsId);
        when(jdbc.queryForObject(eq("SELECT published_version FROM broadcast_publication_cursors WHERE innings_id = ?"), eq(Long.class), eq(inningsId)))
                .thenReturn(18L);
        when(liveScore.execute(inningsId)).thenReturn(score);

        LiveScoreBroadcastPublicationGuard guard = new LiveScoreBroadcastPublicationGuard(jdbc, messaging, liveScore);
        assertFalse(guard.publishIfNewer(new LiveScoreCommittedEvent(inningsId, UUID.randomUUID(), 17, 17, "DELIVERY_RECORDED")));

        verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("UPDATE broadcast_publication_cursors"),
                any(Object[].class));
    }
}
