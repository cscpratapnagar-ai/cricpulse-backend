package com.cricket.platform.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryOutcomeCalculatorTest {

    private static final UUID COMMAND = UUID.randomUUID();
    private static final UUID INNINGS = UUID.randomUUID();
    private static final UUID STRIKER = UUID.randomUUID();
    private static final UUID NON_STRIKER = UUID.randomUUID();
    private static final UUID BOWLER = UUID.randomUUID();

    private static DeliveryCommand command(int batRuns, int extraRuns, String extraType,
            String wicketType) {
        return new DeliveryCommand(COMMAND, INNINGS, STRIKER, NON_STRIKER, BOWLER,
                batRuns, extraRuns, extraType, wicketType, null, null, null);
    }

    @Test
    void normalBatRunsAreBatterAndBowlerRuns() {
        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                command(4, 0, null, null));

        assertEquals(4, outcome.totalRuns());
        assertEquals(4, outcome.batterRuns());
        assertEquals(0, outcome.extraRuns());
        assertEquals(4, outcome.bowlerRunsConceded());
        assertTrue(outcome.legalDelivery());
        assertTrue(outcome.batterFacedBall());
        assertFalse(outcome.oddRunChangesStrike());
    }

    @Test
    void byeAndLegByeDoNotChargeBowler() {
        for (String extra : new String[] {"BYE", "LEG_BYE"}) {
            DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                    command(0, 3, extra, null));

            assertEquals(3, outcome.totalRuns());
            assertEquals(0, outcome.batterRuns());
            assertEquals(3, outcome.extraRuns());
            assertEquals(0, outcome.bowlerRunsConceded());
            assertTrue(outcome.legalDelivery());
            assertTrue(outcome.oddRunChangesStrike());
        }
    }

    @Test
    void penaltyRunsDoNotChargeBowlerAndDoNotChangeStrike() {
        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                command(0, 5, "PENALTY", null));

        assertEquals(5, outcome.totalRuns());
        assertEquals(5, outcome.extraRuns());
        assertEquals(0, outcome.bowlerRunsConceded());
        assertTrue(outcome.legalDelivery());
        assertFalse(outcome.oddRunChangesStrike());
    }

    @Test
    void wideAndNoBallAreNotLegalDeliveries() {
        DeliveryOutcomeCalculator.Outcome wide = DeliveryOutcomeCalculator.calculate(
                command(0, 1, "WIDE", null));
        DeliveryOutcomeCalculator.Outcome noBall = DeliveryOutcomeCalculator.calculate(
                command(2, 1, "NO_BALL", null));

        assertFalse(wide.legalDelivery());
        assertFalse(wide.batterFacedBall());
        assertEquals(1, wide.bowlerRunsConceded());

        assertFalse(noBall.legalDelivery());
        assertFalse(noBall.batterFacedBall());
        assertEquals(3, noBall.bowlerRunsConceded());
    }

    @Test
    void wideRunningExtrasOddTotalChangeStrikeButDoNotCountBall() {
        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                command(0, 3, "WIDE", null));

        assertEquals(3, outcome.totalRuns());
        assertEquals(0, outcome.batterRuns());
        assertEquals(3, outcome.extraRuns());
        assertEquals(3, outcome.bowlerRunsConceded());
        assertFalse(outcome.legalDelivery());
        assertFalse(outcome.batterFacedBall());
        assertFalse(outcome.countsTowardOver());
        assertTrue(outcome.oddRunChangesStrike());
    }

    @Test
    void wideEvenTotalDoesNotChangeStrike() {
        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                command(0, 2, "WIDE", null));

        assertEquals(2, outcome.totalRuns());
        assertEquals(2, outcome.bowlerRunsConceded());
        assertFalse(outcome.legalDelivery());
        assertFalse(outcome.countsTowardOver());
        assertFalse(outcome.oddRunChangesStrike());
    }

    @Test
    void noBallBatRunsAreIncludedInTotalAndBowlerRunsAndOddTotalChangesStrike() {
        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                command(2, 1, "NO_BALL", null));

        assertEquals(3, outcome.totalRuns());
        assertEquals(2, outcome.batterRuns());
        assertEquals(1, outcome.extraRuns());
        assertEquals(3, outcome.bowlerRunsConceded());
        assertFalse(outcome.legalDelivery());
        assertFalse(outcome.batterFacedBall());
        assertFalse(outcome.countsTowardOver());
        assertTrue(outcome.oddRunChangesStrike());
    }

    @Test
    void noBallEvenTotalDoesNotChangeStrike() {
        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(
                command(3, 1, "NO_BALL", null));

        assertEquals(4, outcome.totalRuns());
        assertEquals(3, outcome.batterRuns());
        assertEquals(4, outcome.bowlerRunsConceded());
        assertFalse(outcome.legalDelivery());
        assertFalse(outcome.countsTowardOver());
        assertFalse(outcome.oddRunChangesStrike());
    }

    @Test
    void bowlerWicketClassificationUsesCanonicalTypes() {
        DeliveryOutcomeCalculator.Outcome bowlerWicket = DeliveryOutcomeCalculator.calculate(
                command(0, 0, null, WicketType.BOWLED.name()));
        DeliveryOutcomeCalculator.Outcome runOut = DeliveryOutcomeCalculator.calculate(
                command(0, 0, null, WicketType.RUN_OUT.name()));

        assertTrue(bowlerWicket.bowlerWicket());
        assertFalse(runOut.bowlerWicket());
    }
}
