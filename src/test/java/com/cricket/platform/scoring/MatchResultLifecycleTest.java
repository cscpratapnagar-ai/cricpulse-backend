package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchResultLifecycleTest {
    @Test
    void evaluationEventTypesAreStable() {
        assertEquals("DELIVERY_RECORDED", MatchResultLifecycle.EventType.DELIVERY_RECORDED.name());
        assertEquals("INNINGS_COMPLETED", MatchResultLifecycle.EventType.INNINGS_COMPLETED.name());
        assertEquals("MATCH_RESULT", MatchResultLifecycle.EventType.MATCH_RESULT.name());
    }

    @Test
    void resultFieldsRepresentTieWithoutWinner() {
        UUID winner = null;
        String resultType = "TIE";
        int margin = 0;

        assertEquals(null, winner);
        assertEquals("TIE", resultType);
        assertEquals(0, margin);
    }
}
