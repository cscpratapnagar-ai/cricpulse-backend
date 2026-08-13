package com.cricket.platform.scoring;

import org.springframework.stereotype.Component;

@Component
public class ScoringEngine {
    public Result calculate(DeliveryCommand command, int currentLegalBalls,
                            int currentRuns, int currentWickets,
                            int maxWickets, Integer maxOvers) {
        if (command == null) {
            throw new IllegalArgumentException("Delivery command is required");
        }

        DeliveryOutcomeCalculator.Outcome outcome = DeliveryOutcomeCalculator.calculate(command);
        OverStateCalculator.State over = OverStateCalculator.calculate(currentLegalBalls, outcome);
        StrikeRotationCalculator.Rotation rotation = StrikeRotationCalculator.calculate(
                command.strikerId(), command.nonStrikerId(), outcome.totalRuns(),
                outcome.legalDelivery(),
                over.ballNumber()
        );
        InningsStateCalculator.State innings = InningsStateCalculator.calculate(
                currentRuns + outcome.totalRuns(),
                currentWickets + (command.wicketType() == null ? 0 : 1),
                maxWickets,
                over.legalBalls(),
                maxOvers
        );

        return new Result(outcome, over, rotation, innings);
    }

    public record Result(
            DeliveryOutcomeCalculator.Outcome outcome,
            OverStateCalculator.State over,
            StrikeRotationCalculator.Rotation rotation,
            InningsStateCalculator.State innings
    ) {}
}
