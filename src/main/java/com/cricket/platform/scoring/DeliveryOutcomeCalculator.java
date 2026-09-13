package com.cricket.platform.scoring;

public final class DeliveryOutcomeCalculator {
    private DeliveryOutcomeCalculator() {}

    public static Outcome calculate(DeliveryCommand command) {
        String extra = command.extraType();
        int totalRuns = command.batRuns() + command.extraRuns();
        boolean legal = !"WIDE".equals(extra) && !"NO_BALL".equals(extra);

        int bowlerRuns = switch (extra == null ? "" : extra) {
            case "BYE", "LEG_BYE", "PENALTY" -> 0;
            default -> totalRuns;
        };

        boolean batterBall = legal;
        boolean bowlerWicket = command.wicketType() != null
                && WicketType.BOWLER_WICKETS.contains(command.wicketType());
        boolean oddRunChangesStrike = !"PENALTY".equals(extra) && totalRuns % 2 != 0;

        return new Outcome(
                totalRuns,
                command.batRuns(),
                command.extraRuns(),
                bowlerRuns,
                legal,
                batterBall,
                oddRunChangesStrike,
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
