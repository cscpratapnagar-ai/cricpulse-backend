package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TournamentLifecycleCertificationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TournamentController controller = new TournamentController(jdbc);
    private final UUID tournamentId = UUID.randomUUID();

    @Test
    void draftToActiveRequiresTeamsAndFixture() {
        when(jdbc.queryForObject(
                contains("SELECT COUNT(*) FROM tournament_teams"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(1);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> validate("DRAFT", "ACTIVE")
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(error.getReason().contains("At least 2 teams"));
    }

    @Test
    void draftToActiveIsAllowedWhenMinimumPrerequisitesExist() {
        when(jdbc.queryForObject(
                contains("SELECT COUNT(*) FROM tournament_teams"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(2);
        when(jdbc.queryForObject(
                contains("SELECT COUNT(*) FROM tournament_matches"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(1);

        assertDoesNotThrow(() -> validate("DRAFT", "ACTIVE"));
    }

    @Test
    void activeToCompletedRequiresEveryFixtureCompleted() {
        when(jdbc.queryForObject(
                contains("SELECT COUNT(*) FROM tournament_matches"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(3);
        when(jdbc.queryForObject(
                contains("m.status='COMPLETED'"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(2);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> validate("ACTIVE", "COMPLETED")
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(error.getReason().contains("All tournament fixtures"));
    }

    @Test
    void activeToCompletedIsAllowedOnlyWhenAllFixturesAreCompleted() {
        when(jdbc.queryForObject(
                contains("SELECT COUNT(*) FROM tournament_matches"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(3);
        when(jdbc.queryForObject(
                contains("m.status='COMPLETED'"),
                eq(Integer.class),
                eq(tournamentId))).thenReturn(3);

        assertDoesNotThrow(() -> validate("ACTIVE", "COMPLETED"));
    }

    @Test
    void unsupportedTransitionIsRejected() {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> validate("DRAFT", "COMPLETED")
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Invalid tournament status transition"));
    }

    @Test
    void repeatedStatusIsRejectedAsConflict() {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> validate("ACTIVE", "ACTIVE")
        );

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("already ACTIVE"));
    }

    @Test
    void fixtureStagesAndPairKeysAreNormalizedAndOrderIndependent() throws Exception {
        Method normalizeStage = TournamentController.class.getDeclaredMethod("normalizeStage", String.class);
        normalizeStage.setAccessible(true);
        assertEquals("SEMI_FINAL", normalizeStage.invoke(null, " semi_final "));

        Method pairKey = TournamentController.class.getDeclaredMethod("pairKey", UUID.class, UUID.class);
        pairKey.setAccessible(true);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(pairKey.invoke(null, a, b), pairKey.invoke(null, b, a));
    }

    @Test
    void unsupportedFixtureStageIsRejected() throws Exception {
        Method normalizeStage = TournamentController.class.getDeclaredMethod("normalizeStage", String.class);
        normalizeStage.setAccessible(true);

        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> normalizeStage.invoke(null, "INVALID_STAGE")
        );

        assertInstanceOf(ResponseStatusException.class, thrown.getCause());
        assertEquals(400, ((ResponseStatusException) thrown.getCause()).getStatusCode().value());
    }

    @Test
    void nrrUsesRunsPerSixLegalBallsAndRoundsToThreeDecimals() throws Exception {
        Method calculateNrr = TournamentController.class.getDeclaredMethod(
                "calculateNrr", int.class, int.class, double.class, double.class);
        calculateNrr.setAccessible(true);

        double nrr = (double) calculateNrr.invoke(null, 120, 100, 60.0, 60.0);

        assertEquals(2.0, nrr);
    }

    private void validate(String current, String target) {
        try {
            Method method = TournamentController.class.getDeclaredMethod(
                    "validateTransition", String.class, String.class, UUID.class);
            method.setAccessible(true);
            method.invoke(controller, current, target, tournamentId);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
