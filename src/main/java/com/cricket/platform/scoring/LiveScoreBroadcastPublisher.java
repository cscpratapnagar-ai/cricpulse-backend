package com.cricket.platform.scoring;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class LiveScoreBroadcastPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;
    private final LiveScoreBroadcastPublicationGuard publicationGuard;

    public LiveScoreBroadcastPublisher(ApplicationEventPublisher applicationEventPublisher,
                                       LiveScoreBroadcastPublicationGuard publicationGuard) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.publicationGuard = publicationGuard;
    }

    public void publishAfterCommit(LiveScoreCommittedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(LiveScoreCommittedEvent event) {
        publicationGuard.publishIfNewer(event);
    }
}
