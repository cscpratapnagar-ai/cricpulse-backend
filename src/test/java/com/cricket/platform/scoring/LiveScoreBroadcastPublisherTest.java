package com.cricket.platform.scoring;

import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class LiveScoreBroadcastPublisherTest {

    @Test
    void publishesEventAndDelegatesCommittedEventToVersionGuard() {
        ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        LiveScoreBroadcastPublicationGuard guard = org.mockito.Mockito.mock(LiveScoreBroadcastPublicationGuard.class);
        LiveScoreBroadcastPublisher publisher = new LiveScoreBroadcastPublisher(events, guard);
        LiveScoreCommittedEvent event = new LiveScoreCommittedEvent(
                UUID.randomUUID(), UUID.randomUUID(), 9L, 9, "DELIVERY_RECORDED");

        publisher.publishAfterCommit(event);
        publisher.onCommitted(event);

        verify(events).publishEvent(event);
        verify(guard).publishIfNewer(event);
    }
}
