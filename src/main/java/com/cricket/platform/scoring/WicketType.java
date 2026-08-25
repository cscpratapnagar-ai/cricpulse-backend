package com.cricket.platform.scoring;

import java.util.Set;

/**
 * Canonical wicket types supported by the CricPulse scoring engine.
 *
 * Keep this list centralized so the HTTP scoring path and the event/rule
 * validation path cannot drift apart and reject a wicket type that the
 * scoring engine is intended to support.
 */
public enum WicketType {
    BOWLED,
    CAUGHT,
    LBW,
    RUN_OUT,
    STUMPED,
    HIT_WICKET,
    HIT_BALL_TWICE,
    OBSTRUCTING_THE_FIELD,
    TIMED_OUT,
    RETIRED_HURT;

    public static final Set<String> VALUES = Set.of(
            BOWLED.name(),
            CAUGHT.name(),
            LBW.name(),
            RUN_OUT.name(),
            STUMPED.name(),
            HIT_WICKET.name(),
            HIT_BALL_TWICE.name(),
            OBSTRUCTING_THE_FIELD.name(),
            TIMED_OUT.name(),
            RETIRED_HURT.name()
    );

    public static final Set<String> BOWLER_WICKETS = Set.of(
            BOWLED.name(),
            CAUGHT.name(),
            LBW.name(),
            STUMPED.name(),
            HIT_WICKET.name()
    );
}
