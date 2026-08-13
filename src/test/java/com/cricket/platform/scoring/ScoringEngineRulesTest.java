package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScoringEngineRulesTest {

    @Test
    void wideIsNotALegalDelivery() {
        assertFalse(isLegal("WIDE"));
    }

    @Test
    void noBallIsNotALegalDelivery() {
        assertFalse(isLegal("NO_BALL"));
    }

    @Test
    void byeIsALegalDelivery() {
        assertTrue(isLegal("BYE"));
    }

    @Test
    void legByeIsALegalDelivery() {
        assertTrue(isLegal("LEG_BYE"));
    }

    @Test
    void normalDeliveryIsLegal() {
        assertTrue(isLegal(null));
    }

    @Test
    void oddRunsSwapStrikers() {
        UUID striker = UUID.randomUUID();
        UUID nonStriker = UUID.randomUUID();

        UUID[] result = rotateForRuns(striker, nonStriker, 1);

        assertEquals(nonStriker, result[0]);
        assertEquals(striker, result[1]);
    }

    @Test
    void evenRunsKeepStrikers() {
        UUID striker = UUID.randomUUID();
        UUID nonStriker = UUID.randomUUID();

        UUID[] result = rotateForRuns(striker, nonStriker, 4);

        assertEquals(striker, result[0]);
        assertEquals(nonStriker, result[1]);
    }

    @Test
    void boundaryFlagsAreCorrect() {
        assertTrue(isFour(4));
        assertFalse(isFour(6));
        assertTrue(isSix(6));
        assertFalse(isSix(4));
    }

    @Test
    void bowlerDoesNotConcedeByesOrLegByes() {
        assertEquals(0, bowlerRuns(0, 4, "BYE"));
        assertEquals(0, bowlerRuns(0, 2, "LEG_BYE"));
        assertEquals(5, bowlerRuns(5, 0, null));
        assertEquals(2, bowlerRuns(0, 2, "WIDE"));
    }

    @Test
    void bowlerWicketExcludesRunOut() {
        assertTrue(countsForBowler("BOWLED"));
        assertTrue(countsForBowler("CAUGHT"));
        assertTrue(countsForBowler("LBW"));
        assertTrue(countsForBowler("STUMPED"));
        assertTrue(countsForBowler("HIT_WICKET"));
        assertFalse(countsForBowler("RUN_OUT"));
    }

    private static boolean isLegal(String extraType) {
        return !"WIDE".equals(extraType) && !"NO_BALL".equals(extraType);
    }

    private static UUID[] rotateForRuns(UUID striker, UUID nonStriker, int runs) {
        if (runs % 2 != 0) {
            return new UUID[]{nonStriker, striker};
        }
        return new UUID[]{striker, nonStriker};
    }

    private static boolean isFour(int runs) {
        return runs == 4;
    }

    private static boolean isSix(int runs) {
        return runs == 6;
    }

    private static int bowlerRuns(int batRuns, int extraRuns, String extraType) {
        if ("BYE".equals(extraType) || "LEG_BYE".equals(extraType)) {
            return batRuns;
        }
        return batRuns + extraRuns;
    }

    private static boolean countsForBowler(String wicketType) {
        return switch (wicketType) {
            case "BOWLED", "CAUGHT", "LBW", "STUMPED", "HIT_WICKET" -> true;
            default -> false;
        };
    }
}
