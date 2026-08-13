package com.cricket.platform.scoring;

public final class DeliveryOutcomeCalculator {
    private DeliveryOutcomeCalculator() {}

    public static Outcome calculate(DeliveryCommand command) {
        String extra = command.extraType();
        int totalRuns = command.batRuns() + command.extraRuns();
        boolean legal = !"WIDE".equals(extra) && !"NO_BALL".equals(extra);

        int bowlerRuns = switch (extra == null ? "" : extra) {
            case "BYE", "LEG_BYE" -> 0;
            default -> totalRuns;
        };

        boolean batterBall = legal && !"WIDE".equals(extra);
        boolean bowlerWicket = command.wicketType() != null
                && switch (command.wicketType()) {
                    case "BOWLED", "CAUGHT", "LBW", "STUMPED", "HIT_WICKET" -> true;
                    default -> false;
                };

        return new Outcome(
                totalRuns,
                command.batRuns(),
                command.extraRuns(),
                bowlerRuns,
                legal,
                batterBall,
                totalRuns % 2 != 0,
                bowlerWicket,
                legal
        );
    }

    public record Outcome(
            int totalRuns,
            int batterRuns,
            int extraRuns,
            int bowlerRunsConceded,
            boolean legalDelivery,
            boolean batterFacedBall,
            boolean oddRunChangesStrike,
            boolean bowlerWicket,
            boolean countsTowardOver
    ) {}
}
