package com.cricket.platform.scoring;

import java.util.Set;

public final class CricketDeliveryRuleValidator {
    private static final Set<String> EXTRAS = Set.of("WIDE", "NO_BALL", "BYE", "LEG_BYE", "PENALTY");
    private static final Set<String> WICKETS = Set.of(
            "BOWLED", "CAUGHT", "LBW", "RUN_OUT", "STUMPED", "HIT_WICKET", "RETIRED_HURT"
    );

    private CricketDeliveryRuleValidator() {
    }

    public static void validate(DeliveryCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Delivery command is required");
        }
        if (command.strikerId().equals(command.nonStrikerId())) {
            throw new IllegalArgumentException("Striker and non-striker must be different");
        }
        if (command.batRuns() < 0 || command.batRuns() > 6) {
            throw new IllegalArgumentException("Bat runs must be between 0 and 6");
        }
        if (command.extraRuns() < 0) {
            throw new IllegalArgumentException("Extra runs cannot be negative");
        }
        if (command.extraType() != null && !EXTRAS.contains(command.extraType())) {
            throw new IllegalArgumentException("Unsupported extra type: " + command.extraType());
        }
        if (command.wicketType() != null && !WICKETS.contains(command.wicketType())) {
            throw new IllegalArgumentException("Unsupported wicket type: " + command.wicketType());
        }
        if (command.extraRuns() > 0 && command.extraType() == null) {
            throw new IllegalArgumentException("Extra type is required when extra runs are recorded");
        }
        if ("WIDE".equals(command.extraType())) {
            if (command.extraRuns() < 1) {
                throw new IllegalArgumentException("Wide must contain at least one extra run");
            }
            if (command.batRuns() != 0) {
                throw new IllegalArgumentException("Wide cannot contain bat runs");
            }
        }
        if ("NO_BALL".equals(command.extraType()) && command.extraRuns() < 1) {
            throw new IllegalArgumentException("No-ball must contain at least one extra run");
        }
        if (command.wicketType() != null && command.dismissedPlayerId() == null) {
            throw new IllegalArgumentException("Dismissed player is required for a wicket");
        }
    }

    public static boolean isLegalDelivery(DeliveryCommand command) {
        return !"WIDE".equals(command.extraType()) && !"NO_BALL".equals(command.extraType());
    }
}
