package com.cricket.platform.match;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchResultServiceTest {
    @Test
    void persistedWinnerDeterminesDetailedWinType() throws Exception {
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        Object match = matchState(matchId, teamA, teamB, teamB, "WIN", "Team B won by 8 wickets");
        Object first = innings(1, teamA, "Team A", 120, 7, 60, 10, "COMPLETED");
        Object second = innings(2, teamB, "Team B", 121, 2, 45, 10, "COMPLETED");
        MatchResultService.Result result = invokeToResult(match, first, second);

        assertEquals("WIN_BY_WICKETS", result.resultType());
        assertEquals(teamB, result.winningTeamId());
        assertEquals(120, result.firstInnings().runs());
        assertEquals(121, result.secondInnings().runs());
    }

    @Test
    void persistedFirstInningsWinnerIsClassifiedAsRunsVictory() throws Exception {
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        Object match = matchState(UUID.randomUUID(), teamA, teamB, teamA, "WIN", "Team A won by 10 runs");
        Object first = innings(1, teamA, "Team A", 130, 8, 60, 10, "COMPLETED");
        Object second = innings(2, teamB, "Team B", 120, 9, 60, 10, "COMPLETED");

        MatchResultService.Result result = invokeToResult(match, first, second);
        assertEquals("WIN_BY_RUNS", result.resultType());
        assertEquals(teamA, result.winningTeamId());
    }

    @Test
    void tieHasNoWinnerAndTieType() throws Exception {
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        Object match = matchState(UUID.randomUUID(), teamA, teamB, null, "TIE", "Match tied");
        Object first = innings(1, teamA, "Team A", 100, 8, 60, 10, "COMPLETED");
        Object second = innings(2, teamB, "Team B", 100, 9, 60, 10, "COMPLETED");

        MatchResultService.Result result = invokeToResult(match, first, second);
        assertEquals("TIE", result.resultType());
        assertNull(result.winningTeamId());
    }

    private MatchResultService.Result invokeToResult(Object match, Object first, Object second) throws Exception {
        MatchResultService service = new MatchResultService(null);
        Method method = MatchResultService.class.getDeclaredMethod("toResult", match.getClass(), first.getClass(), second.getClass());
        method.setAccessible(true);
        return (MatchResultService.Result) method.invoke(service, match, first, second);
    }

    private Object matchState(UUID id, UUID teamA, UUID teamB, UUID winner, String type, String text) throws Exception {
        Class<?> clazz = Class.forName("com.cricket.platform.match.MatchResultService$MatchState");
        Constructor<?> constructor = clazz.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(id, "Test Match", "T20", "COMPLETED", teamA, "Team A", teamB, "Team B",
                10, winner, type, text, teamA, "BAT");
    }

    private Object innings(int number, UUID team, String name, int runs, int wickets, int balls, Integer overs, String status) throws Exception {
        Class<?> clazz = Class.forName("com.cricket.platform.match.MatchResultService$InningsScore");
        Constructor<?> constructor = clazz.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(number, team, name, runs, wickets, balls, overs, status);
    }
}
