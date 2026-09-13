package com.cricket.platform.scoring;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Enforces match-level player eligibility before a delivery mutates scoring state.
 * Cricket rule validation alone cannot prove that a UUID belongs to this match's
 * Playing XI, so these checks live at the persistence boundary.
 */
@Component
final class DeliveryParticipantValidator {
    private final JdbcTemplate jdbc;

    DeliveryParticipantValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void validate(UUID inningsId, UUID strikerId, UUID nonStrikerId, UUID bowlerId,
            UUID newBatterId, UUID dismissedPlayerId, String wicketType) {
        MatchTeams teams = jdbc.queryForObject(
                "SELECT i.batting_team_id, m.team_a_id, m.team_b_id "
                        + "FROM innings i JOIN matches m ON m.id=i.match_id WHERE i.id=?",
                (rs, rowNum) -> new MatchTeams(
                        rs.getObject("batting_team_id", UUID.class),
                        rs.getObject("team_a_id", UUID.class),
                        rs.getObject("team_b_id", UUID.class)),
                inningsId);

        if (teams == null) {
            throw new IllegalArgumentException("Match context was not found for innings");
        }

        UUID bowlingTeamId = teams.battingTeamId().equals(teams.teamAId())
                ? teams.teamBId() : teams.teamAId();

        requirePlayingXi(inningsId, teams.battingTeamId(), strikerId, "Striker");
        requirePlayingXi(inningsId, teams.battingTeamId(), nonStrikerId, "Non-striker");
        requirePlayingXi(inningsId, bowlingTeamId, bowlerId, "Bowler");

        if (wicketType != null && newBatterId != null) {
            requirePlayingXi(inningsId, teams.battingTeamId(), newBatterId, "New batter");
            Integer dismissed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM innings_batters "
                            + "WHERE innings_id=? AND player_id=? AND is_out=TRUE",
                    Integer.class, inningsId, newBatterId);
            if (dismissed != null && dismissed > 0) {
                throw new IllegalArgumentException("New batter has already been dismissed in this innings");
            }
        }

        if (dismissedPlayerId != null) {
            requirePlayingXi(inningsId, teams.battingTeamId(), dismissedPlayerId, "Dismissed player");
        }
    }

    private void requirePlayingXi(UUID inningsId, UUID teamId, UUID playerId, String role) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM innings i "
                        + "JOIN match_players mp ON mp.match_id=i.match_id "
                        + "WHERE i.id=? AND mp.team_id=? AND mp.player_id=?",
                Integer.class, inningsId, teamId, playerId);
        if (count == null || count != 1) {
            throw new IllegalArgumentException(role + " is not in the selected Playing XI for this innings");
        }
    }

    private record MatchTeams(UUID battingTeamId, UUID teamAId, UUID teamBId) {
    }
}
