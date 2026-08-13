package com.cricket.platform.scoring;

import java.util.UUID;

public final class StrikeRotationCalculator {
    private StrikeRotationCalculator() {}

    public static Rotation calculate(UUID strikerId, UUID nonStrikerId,
                                     int totalRuns, boolean legalDelivery,
                                     int legalBallNumber) {
        UUID striker = strikerId;
        UUID nonStriker = nonStrikerId;

        if ((totalRuns & 1) == 1) {
            UUID temp = striker;
            striker = nonStriker;
            nonStriker = temp;
        }

        if (legalDelivery && legalBallNumber == 6) {
            UUID temp = striker;
            striker = nonStriker;
            nonStriker = temp;
        }

        return new Rotation(striker, nonStriker);
    }

    public record Rotation(UUID strikerId, UUID nonStrikerId) {}
}
