package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InningsLifecycleTest {
    private static final UUID INNINGS_ID = UUID.randomUUID();

    @Test
    void targetReachedTakesPrecedenceWhenLastWicketFallsOnSameDelivery() {
        assertCompletion(state(101, 10, 120, 20, 101, "LIVE"), "TARGET_REACHED");
    }

    @Test
    void targetReachedThroughExtrasCompletesInnings() {
        assertCompletion(state(151, 3, 87, 20, 151, "LIVE"), "TARGET_REACHED");
    }

    @Test
    void allOutCompletesEvenBeforeOversAreFinished() {
        assertCompletion(state(42, 10, 23, 20, null, "LIVE"), "ALL_OUT");
    }

    @Test
    void exactlyConfiguredOversCompletesInnings() {
        assertCompletion(state(90, 4, 120, 20, null, "LIVE"), "OVERS_COMPLETED");
    }

    @Test
    void oversLimitReachedOrExceededCompletesInnings() {
        assertCompletion(state(90, 4, 121, 20, null, "LIVE"), "OVERS_COMPLETED");
    }

    @Test
    void alreadyCompletedInningsIsNotMutatedAgain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InningsLifecycle lifecycle = new InningsLifecycle(jdbc);
        stubState(jdbc, state(100, 5, 100, 20, 101, "COMPLETED"));

        InningsLifecycle.Completion result = lifecycle.evaluate(INNINGS_ID);

        assertFalse(result.completed());
        assertEquals("COMPLETED", result.status());
        assertNull(result.reason());
        verify(jdbc, never()).update(anyString(), eq(INNINGS_ID));
    }

    private static void assertCompletion(Fixture fixture, String expectedReason) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InningsLifecycle lifecycle = new InningsLifecycle(jdbc);
        stubState(jdbc, fixture);

        when(jdbc.update(anyString(), eq(INNINGS_ID))).thenReturn(1);

        InningsLifecycle.Completion result = lifecycle.evaluate(INNINGS_ID);

        assertTrue(result.completed());
        assertEquals("COMPLETED", result.status());
        assertEquals(expectedReason, result.reason());
        verify(jdbc).update(contains("status = 'COMPLETED'"), INNINGS_ID);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubState(JdbcTemplate jdbc, Fixture fixture) {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("total_runs")).thenReturn(fixture.runs());
        when(rs.getInt("wickets")).thenReturn(fixture.wickets());
        when(rs.getInt("legal_balls")).thenReturn(fixture.legalBalls());
        when(rs.getObject("total_overs")).thenReturn(fixture.overs());
        when(rs.getObject("target_runs")).thenReturn(fixture.target());
        when(rs.getString("status")).thenReturn(fixture.status());

        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(INNINGS_ID)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(rs, 0));
    }

    private static Fixture state(int runs, int wickets, int legalBalls,
                                 Integer overs, Integer target, String status) {
        return new Fixture(runs, wickets, legalBalls, overs, target, status);
    }

    private record Fixture(int runs, int wickets, int legalBalls,
                          Integer overs, Integer target, String status) {}
}
