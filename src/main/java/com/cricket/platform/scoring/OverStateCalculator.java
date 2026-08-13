package com.cricket.platform.scoring;

public final class OverStateCalculator {
    private OverStateCalculator() {}

    public static State calculate(int currentLegalBalls, DeliveryOutcomeCalculator.Outcome outcome) {
        if (currentLegalBalls < 0) {
            throw new IllegalArgumentException("Current legal balls cannot be negative");
        }

        int legalBalls = currentLegalBalls + (outcome.countsTowardOver() ? 1 : 0);
        int overNumber = legalBalls / 6;
        int ballNumber = legalBalls == 0 ? 0 : ((legalBalls - 1) % 6) + 1;
        boolean overComplete = legalBalls > 0 && legalBalls % 6 == 0;

        return new State(legalBalls, overNumber, ballNumber, overComplete);
    }

    public record State(int legalBalls, int overNumber, int ballNumber, boolean overComplete) {}
}
