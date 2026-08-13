package com.cricket.platform.scoring;

public final class InningsStateCalculator {
    private InningsStateCalculator() {}

    public static State calculate(int currentRuns, int currentWickets,
                                  int maxWickets, int legalBalls,
                                  Integer maxOvers) {
        if (currentRuns < 0 || currentWickets < 0 || legalBalls < 0) {
            throw new IllegalArgumentException("Innings state cannot be negative");
        }
        if (maxWickets < 1) {
            throw new IllegalArgumentException("Max wickets must be positive");
        }

        boolean allOut = currentWickets >= maxWickets;
        boolean oversComplete = maxOvers != null && legalBalls >= maxOvers * 6;
        boolean inningsComplete = allOut || oversComplete;

        return new State(currentRuns, currentWickets, legalBalls,
                allOut, oversComplete, inningsComplete);
    }

    public record State(int runs, int wickets, int legalBalls,
                        boolean allOut, boolean oversComplete,
                        boolean inningsComplete) {}
}
