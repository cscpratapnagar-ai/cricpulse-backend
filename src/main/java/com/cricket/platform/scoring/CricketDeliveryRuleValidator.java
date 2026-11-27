package com.cricket.platform.scoring;

public final class CricketDeliveryRuleValidator {
    private static final java.util.Set<String> NO_BALL_DISMISSALS = java.util.Set.of(
            WicketType.RUN_OUT.name(),
            WicketType.OBSTRUCTING_THE_FIELD.name(),
            WicketType.HIT_BALL_TWICE.name());
    private static final java.util.Set<String> WIDE_DISMISSALS = java.util.Set.of(
            WicketType.HIT_WICKET.name(),
            WicketType.OBSTRUCTING_THE_FIELD.name(),
            WicketType.RUN_OUT.name(),
            WicketType.STUMPED.name());

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
        if (command.extraType() != null && !java.util.Set.of("WIDE", "NO_BALL", "BYE", "LEG_BYE", "PENALTY").contains(command.extraType())) {
            throw new IllegalArgumentException("Unsupported extra type: " + command.extraType());
        }
        if (command.wicketType() != null && !WicketType.DELIVERY_WICKETS.contains(command.wicketType())) {
            throw new IllegalArgumentException("Unsupported delivery wicket type: " + command.wicketType());
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
        validateWicketDelivery(command.wicketType(), command.extraType());
        if (command.wicketType() != null && command.dismissedPlayerId() == null) {
            throw new IllegalArgumentException("Dismissed player is required for a wicket");
        }
        if (command.wicketType() != null) {
            validateDismissedPlayer(command.strikerId(), command.nonStrikerId(),
                    command.dismissedPlayerId(), command.wicketType());
        }
    }

    public static void validateDismissedPlayer(
            java.util.UUID strikerId,
            java.util.UUID nonStrikerId,
            java.util.UUID dismissedPlayerId,
            String wicketType) {
        if (dismissedPlayerId == null) {
            throw new IllegalArgumentException("Dismissed player is required for a wicket");
        }
        if (dismissedPlayerId.equals(strikerId)) {
            return;
        }
        if (!dismissedPlayerId.equals(nonStrikerId)) {
            throw new IllegalArgumentException(
                    "Dismissed player must be the current striker or non-striker");
        }
        if (!WicketType.RUN_OUT.name().equals(wicketType)
                && !WicketType.OBSTRUCTING_THE_FIELD.name().equals(wicketType)) {
            throw new IllegalArgumentException(
                    wicketType + " can only dismiss the striker, not the non-striker");
        }
    }

    public static void validateWicketDelivery(String wicketType, String extraType) {
        if (wicketType == null) {
            return;
        }
        if ("NO_BALL".equals(extraType) && !NO_BALL_DISMISSALS.contains(wicketType)) {
            throw new IllegalArgumentException(
                    "A batter cannot be dismissed as " + wicketType + " from a no-ball");
        }
        if ("WIDE".equals(extraType) && !WIDE_DISMISSALS.contains(wicketType)) {
            throw new IllegalArgumentException(
                    "A batter cannot be dismissed as " + wicketType + " from a wide");
        }
    }

    public static boolean isLegalDelivery(DeliveryCommand command) {
        return !"WIDE".equals(command.extraType()) && !"NO_BALL".equals(command.extraType());
    }
}
