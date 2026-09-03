package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InningsLifecycleTest {
    @Test
    void targetReachedTakesPrecedenceOverOtherCompletionRules() throws Exception {
        InningsLifecycle lifecycle = new InningsLifecycle((JdbcTemplate) null);
        Method method = InningsLifecycle.class.getDeclaredMethod("completionReason", InningsLifecycle.State.class);
        method.setAccessible(true);

        Object reason = method.invoke(lifecycle,
                newState(101, 9, 60, 10, 101, "LIVE"));

        assertEquals("TARGET_REACHED", reason);
    }

    @Test
    void allOutCompletesWhenTargetIsNotReached() throws Exception {
        InningsLifecycle lifecycle = new InningsLifecycle((JdbcTemplate) null);
        Method method = InningsLifecycle.class.getDeclaredMethod("completionReason", InningsLifecycle.State.class);
        method.setAccessible(true);

        Object reason = method.invoke(lifecycle,
                newState(80, 10, 55, 20, 150, "LIVE"));

        assertEquals("ALL_OUT", reason);
    }

    @Test
    void oversCompleteAfterSixLegalBallsPerOver() throws Exception {
        InningsLifecycle lifecycle = new InningsLifecycle((JdbcTemplate) null);
        Method method = InningsLifecycle.class.getDeclaredMethod("completionReason", InningsLifecycle.State.class);
        method.setAccessible(true);

        Object reason = method.invoke(lifecycle,
                newState(120, 4, 120, 20, 250, "LIVE"));

        assertEquals("OVERS_COMPLETED", reason);
    }

    private InningsLifecycle.State newState(int totalRuns, int wickets, int legalBalls,
                                            Integer totalOvers, Integer targetRuns, String status) {
        try {
            var constructor = InningsLifecycle.class.getDeclaredClasses()[0].getDeclaredConstructor(
                    int.class, int.class, int.class, Integer.class, Integer.class, String.class);
            constructor.setAccessible(true);
            return (InningsLifecycle.State) constructor.newInstance(
                    totalRuns, wickets, legalBalls, totalOvers, targetRuns, status);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
