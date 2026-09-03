package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoringLifecycleEventTypeTest {
    @Test
    void lifecycleEventNamesRemainStable() {
        assertEquals("DELIVERY_RECORDED", "DELIVERY_RECORDED");
        assertEquals("INNINGS_COMPLETED", "INNINGS_COMPLETED");
        assertEquals("MATCH_RESULT", "MATCH_RESULT");
    }
}
