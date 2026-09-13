package com.cricket.platform.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class GetPlayerIntelligenceTest {
    @Test
    void summarizesRecentCompletedMatchesWithoutInventingScore() {
        GetPlayerStatistics statistics = mock(GetPlayerStatistics.class);
        GetPlayerPerformanceHistory history = mock(GetPlayerPerformanceHistory.class);
        UUID playerId = UUID.randomUUID();
        GetPlayerStatistics.PlayerStatistics career = new GetPlayerStatistics.PlayerStatistics(
                playerId, "Test Player", 4, 4, 180, 80, 3, 18, 6, 140,
                BigDecimal.valueOf(60), BigDecimal.valueOf(128.57),
                24, 30, 3, 2, BigDecimal.valueOf(7.5));
        when(statistics.one(playerId)).thenReturn(career);
        List<GetPlayerPerformanceHistory.MatchPerformance> matches = List.of(
                new GetPlayerPerformanceHistory.MatchPerformance(UUID.randomUUID(), "M1", "T20", "COMPLETED", null, OffsetDateTime.now(), "A", "B", 60, 40, 6, 2, 1, 12, 15, 2),
                new GetPlayerPerformanceHistory.MatchPerformance(UUID.randomUUID(), "M2", "T20", "COMPLETED", null, OffsetDateTime.now(), "A", "B", 40, 30, 3, 1, 1, 6, 8, 1),
                new GetPlayerPerformanceHistory.MatchPerformance(UUID.randomUUID(), "M3", "T20", "COMPLETED", null, OffsetDateTime.now(), "A", "B", 20, 25, 1, 0, 1, 6, 9, 0));
        when(history.recent(playerId, 10)).thenReturn(matches);

        GetPlayerIntelligence intelligence = new GetPlayerIntelligence(statistics, history);
        GetPlayerIntelligence.Intelligence result = intelligence.get(playerId, 10);

        assertEquals(3, result.sampleMatches());
        assertEquals("IMPROVING", result.form());
        assertEquals(120, result.recentRuns());
        assertEquals(3, result.recentWickets());
        assertEquals(180, result.careerRuns());
        assertEquals(24, result.careerWickets());
        assertEquals("Based only on completed matches", result.dataBasis());
    }
}
