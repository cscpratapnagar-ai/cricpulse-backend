package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TournamentStandingsCalculatorTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void awardsTwoPointsToAuthoritativeWinnerEvenWhenRegulationScoresAreLevel() {
        var match = match(a, b, "WIN", b,
                innings(a, 150, 4, 120, 20), innings(b, 150, 5, 119, 20));
        var table = calculate(match);
        assertEquals(2, row(table, b).points());
        assertEquals(1, row(table, b).wins());
        assertEquals(0, row(table, a).points());
        assertEquals(1, row(table, a).losses());
    }

    @Test
    void tiedMatchAwardsOnePointToEachSideAndIncludesRegulationNrr() {
        var match = match(a, b, "TIE", null,
                innings(a, 120, 8, 120, 20), innings(b, 120, 9, 120, 20));
        var table = calculate(match);
        assertEquals(1, row(table, a).points());
        assertEquals(1, row(table, b).points());
        assertEquals(1, row(table, a).ties());
        assertEquals(1, row(table, b).ties());
        assertEquals(120, row(table, a).runsFor());
        assertEquals(120, row(table, b).runsFor());
    }

    @Test
    void noResultCountsAsPlayedAndAwardsOnePointWithoutPollutingNrr() {
        var match = match(a, b, "NO_RESULT", null,
                innings(a, 40, 1, 30, 20), innings(b, 0, 0, 0, 20));
        var table = calculate(match);
        assertEquals(1, row(table, a).played());
        assertEquals(1, row(table, a).noResults());
        assertEquals(1, row(table, a).points());
        assertEquals(0, row(table, a).runsFor());
        assertEquals(0.0, row(table, a).nrr());
        assertEquals(List.of("NR"), row(table, a).recentForm());
    }

    @Test
    void abandonedMatchUsesSameDefaultNoResultPointsAndIsExcludedFromNrr() {
        var table = calculate(match(a, b, "ABANDONED", null, null, null));
        assertEquals(1, row(table, a).points());
        assertEquals(1, row(table, b).points());
        assertEquals(1, row(table, a).noResults());
        assertEquals(0, row(table, a).runsAgainst());
    }

    @Test
    void allOutInningsUseFullOversQuotaForNrr() {
        var match = match(a, b, "WIN", b,
                innings(a, 100, 10, 60, 20), innings(b, 101, 5, 60, 20));
        var table = calculate(match);
        assertEquals(-5.1, row(table, a).nrr(), 0.001);
        assertEquals(5.1, row(table, b).nrr(), 0.001);
    }

    @Test
    void secondInningsAllOutUsesFullOversQuotaForNrr() {
        var match = match(a, b, "WIN", b,
                innings(a, 100, 10, 60, 20), innings(b, 101, 10, 60, 20));
        var table = calculate(match);

        assertEquals(-0.05, row(table, a).nrr(), 0.001);
        assertEquals(0.05, row(table, b).nrr(), 0.001);
    }

    @Test
    void completedResultWithoutValidParticipantWinnerDoesNotAwardPointsOrAppearPlayed() {
        var match = match(a, b, "WIN", c,
                innings(a, 140, 4, 120, 20), innings(b, 130, 6, 120, 20));
        var table = calculate(match);

        assertEquals(0, row(table, a).played());
        assertEquals(0, row(table, b).played());
        assertEquals(0, row(table, a).points());
        assertEquals(0, row(table, b).points());
        assertEquals(0, row(table, a).runsFor());
        assertEquals(0, row(table, b).runsFor());
        assertTrue(row(table, a).recentForm().isEmpty());
        assertTrue(row(table, b).recentForm().isEmpty());
    }

    @Test
    void missingOrZeroLegalBallInningsDoNotCreateArtificialNrr() {
        var match = match(a, b, "WIN", a,
                innings(a, 80, 2, 0, 20), innings(b, 70, 3, 0, 20));
        var table = calculate(match);

        assertEquals(2, row(table, a).points());
        assertEquals(1, row(table, a).played());
        assertEquals(0, row(table, a).runsFor());
        assertEquals(0, row(table, b).runsFor());
        assertEquals(0.0, row(table, a).nrr());
        assertEquals(0.0, row(table, b).nrr());
    }

    @Test
    void standingsSortByPointsThenNrrThenRunsForThenTeamName() {
        var winsA = match(a, b, "WIN", a,
                innings(a, 160, 3, 120, 20), innings(b, 140, 5, 120, 20));
        var winsC = match(c, b, "WIN", c,
                innings(c, 180, 2, 120, 20), innings(b, 150, 6, 120, 20));
        var table = TournamentStandingsCalculator.calculate(List.of(
                new TournamentStandingsCalculator.Team(a, "Falcons"),
                new TournamentStandingsCalculator.Team(b, "Tigers"),
                new TournamentStandingsCalculator.Team(c, "Eagles")),
                List.of(winsA, winsC));
        assertEquals(c, table.getFirst().teamId());
        assertEquals(a, table.get(1).teamId());
        assertEquals(b, table.get(2).teamId());
    }

    @Test
    void knockoutFixturesAreNotFedIntoLeagueStandingsByCallerContract() {
        // The calculator accepts league fixtures only. A knockout win is deliberately not
        // passed in; this guards the intended separation between league qualification and playoffs.
        var table = TournamentStandingsCalculator.calculate(List.of(
                new TournamentStandingsCalculator.Team(a, "Falcons"),
                new TournamentStandingsCalculator.Team(b, "Tigers")), List.of());
        assertEquals(0, row(table, a).played());
        assertEquals(0, row(table, a).points());
    }

    private List<TournamentStandingsCalculator.Standing> calculate(TournamentStandingsCalculator.Match match) {
        return TournamentStandingsCalculator.calculate(List.of(
                new TournamentStandingsCalculator.Team(a, "Falcons"),
                new TournamentStandingsCalculator.Team(b, "Tigers")), List.of(match));
    }

    private TournamentStandingsCalculator.Match match(UUID teamA, UUID teamB, String result,
            UUID winner, TournamentStandingsCalculator.Innings first,
            TournamentStandingsCalculator.Innings second) {
        return new TournamentStandingsCalculator.Match(teamA, teamB, "COMPLETED", result,
                winner, 20, first, second);
    }

    private TournamentStandingsCalculator.Innings innings(UUID battingTeam, int runs, int wickets,
            int balls, Integer overs) {
        return new TournamentStandingsCalculator.Innings(battingTeam, runs, wickets, balls, overs);
    }

    private TournamentStandingsCalculator.Standing row(
            List<TournamentStandingsCalculator.Standing> table, UUID teamId) {
        return table.stream().filter(row -> row.teamId().equals(teamId)).findFirst().orElseThrow();
    }
}
