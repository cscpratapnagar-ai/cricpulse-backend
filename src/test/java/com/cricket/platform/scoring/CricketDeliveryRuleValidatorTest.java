package com.cricket.platform.scoring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CricketDeliveryRuleValidatorTest {

    private static final UUID COMMAND = UUID.randomUUID();
    private static final UUID INNINGS = UUID.randomUUID();
    private static final UUID STRIKER = UUID.randomUUID();
    private static final UUID NON_STRIKER = UUID.randomUUID();
    private static final UUID BOWLER = UUID.randomUUID();
    private static final UUID DISMISSED = UUID.randomUUID();

    private static DeliveryCommand command(int batRuns, int extraRuns, String extraType,
            String wicketType, UUID dismissedPlayerId) {
        return new DeliveryCommand(
                COMMAND,
                INNINGS,
                STRIKER,
                NON_STRIKER,
                BOWLER,
                batRuns,
                extraRuns,
                extraType,
                wicketType,
                dismissedPlayerId,
                null,
                null);
    }

    @Test
    void acceptsNormalBatRunsZeroThroughSix() {
        for (int runs = 0; runs <= 6; runs++) {
            assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(
                    command(runs, 0, null, null, null)));
            assertTrue(CricketDeliveryRuleValidator.isLegalDelivery(
                    command(runs, 0, null, null, null)));
        }
    }

    @Test
    void rejectsNegativeExtraRuns() {
        assertThrows(IllegalArgumentException.class,
                () -> command(0, -1, null, null, null));
    }

    @Test
    void rejectsUnsupportedExtraType() {
        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(command(0, 1, "UNKNOWN", null, null)));
    }

    @Test
    void requiresExtraTypeWhenExtraRunsArePresent() {
        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(command(0, 1, null, null, null)));
    }

    @Test
    void validatesWideRules() {
        assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(command(0, 1, "WIDE", null, null)));
        assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(command(0, 5, "WIDE", null, null)));
        assertFalse(CricketDeliveryRuleValidator.isLegalDelivery(command(0, 1, "WIDE", null, null)));

        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(command(0, 0, "WIDE", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(command(1, 1, "WIDE", null, null)));
    }

    @Test
    void validatesNoBallRules() {
        assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(command(0, 1, "NO_BALL", null, null)));
        assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(command(6, 1, "NO_BALL", null, null)));
        assertFalse(CricketDeliveryRuleValidator.isLegalDelivery(command(0, 1, "NO_BALL", null, null)));

        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(command(0, 0, "NO_BALL", null, null)));
    }

    @Test
    void validatesLegalExtras() {
        for (String extraType : new String[] {"BYE", "LEG_BYE", "PENALTY"}) {
            assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(
                    command(0, 1, extraType, null, null)));
            assertTrue(CricketDeliveryRuleValidator.isLegalDelivery(
                    command(0, 1, extraType, null, null)));
        }
    }

    @Test
    void validatesCanonicalWicketTypes() {
        for (String wicketType : WicketType.VALUES) {
            assertDoesNotThrow(() -> CricketDeliveryRuleValidator.validate(
                    command(0, 0, null, wicketType, DISMISSED)));
        }
    }

    @Test
    void requiresDismissedPlayerForWicket() {
        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(
                        command(0, 0, null, WicketType.BOWLED.name(), null)));
    }

    @Test
    void rejectsUnsupportedWicketType() {
        assertThrows(IllegalArgumentException.class,
                () -> CricketDeliveryRuleValidator.validate(
                        command(0, 0, null, "UNKNOWN_WICKET", DISMISSED)));
    }
}
