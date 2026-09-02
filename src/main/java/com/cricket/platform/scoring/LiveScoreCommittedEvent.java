package com.cricket.platform.scoring;

import java.util.UUID;

/**
 * Application event raised after a scoring mutation has been persisted and projected.
 * The transaction listener publishes the authoritative score only after commit.
 */
public record LiveScoreCommittedEvent(
        UUID inningsId,
        UUID eventId,
        long sequenceNo,
        int eventVersion,
        String eventType
) {}
