package com.cricket.platform.scoring;

import java.util.Set;

/**
 * Canonical wicket/dismissal types known to CricPulse.
 *
 * Timed-out and retired-hurt are match-state events, not ball-delivery
 * dismissals. They stay in the canonical set for API/domain vocabulary, but
 * the delivery engine must reject them until a dedicated lifecycle command is
 * implemented.
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

    public static final Set<String> DELIVERY_WICKETS = Set.of(
            BOWLED.name(),
            CAUGHT.name(),
            LBW.name(),
            RUN_OUT.name(),
            STUMPED.name(),
            HIT_WICKET.name(),
            HIT_BALL_TWICE.name(),
            OBSTRUCTING_THE_FIELD.name()
    );

    public static final Set<String> BOWLER_WICKETS = Set.of(
            BOWLED.name(),
            CAUGHT.name(),
            LBW.name(),
            STUMPED.name(),
            HIT_WICKET.name()
    );
}
