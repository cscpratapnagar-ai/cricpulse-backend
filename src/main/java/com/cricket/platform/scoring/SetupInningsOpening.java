package com.cricket.platform.scoring;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class SetupInningsOpening {
    private final JdbcTemplate jdbc;

    public SetupInningsOpening(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public OpeningResponse execute(Request request) {
        InningsState innings = jdbc.queryForObject(
                "SELECT batting_team_id, bowling_team_id, status, striker_id, non_striker_id, current_bowler_id FROM innings WHERE id = ? FOR UPDATE",
                (rs, row) -> new InningsState(
                        rs.getObject("batting_team_id", UUID.class),
                        rs.getObject("bowling_team_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("striker_id", UUID.class),
                        rs.getObject("non_striker_id", UUID.class),
                        rs.getObject("current_bowler_id", UUID.class)
                ),
                request.inningsId()
        );

        if (innings == null) {
            throw new IllegalArgumentException("Innings was not found");
        }

        if (!"LIVE".equals(innings.status())) {
            throw new IllegalArgumentException("Innings is not live");
        }

        if (innings.strikerId() != null || innings.nonStrikerId() != null || innings.currentBowlerId() != null) {
            throw new IllegalArgumentException("Opening players are already configured");
        }

        if (request.strikerId().equals(request.nonStrikerId())) {
            throw new IllegalArgumentException("Striker and non-striker must be different players");
        }

        requirePlayingPlayer(request.inningsId(), innings.battingTeamId(), request.strikerId());
        requirePlayingPlayer(request.inningsId(), innings.battingTeamId(), request.nonStrikerId());
        requirePlayingPlayer(request.inningsId(), innings.bowlingTeamId(), request.bowlerId());

        jdbc.update(
                "UPDATE innings SET striker_id = ?, non_striker_id = ?, current_bowler_id = ? WHERE id = ?",
                request.strikerId(), request.nonStrikerId(), request.bowlerId(), request.inningsId()
        );

        return new OpeningResponse(
                request.inningsId(),
                request.strikerId(),
                request.nonStrikerId(),
                request.bowlerId(),
                "READY_FOR_FIRST_BALL"
        );
    }

    private void requirePlayingPlayer(UUID inningsId, UUID teamId, UUID playerId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM innings i
                JOIN match_players mp ON mp.match_id = i.match_id
                                      AND mp.team_id = ?
                                      AND mp.player_id = ?
                                      AND mp.is_playing_xi = TRUE
                WHERE i.id = ?
                """,
                Integer.class,
                teamId,
                playerId,
                inningsId
        );

        if (count == null || count == 0) {
            throw new IllegalArgumentException("Player is not in the selected Playing XI for this innings");
        }
    }

    private record InningsState(
            UUID battingTeamId,
            UUID bowlingTeamId,
            String status,
            UUID strikerId,
            UUID nonStrikerId,
            UUID currentBowlerId
    ) {}

    public record Request(
            @NotNull UUID inningsId,
            @NotNull UUID strikerId,
            @NotNull UUID nonStrikerId,
            @NotNull UUID bowlerId
    ) {}

    public record OpeningResponse(
            UUID inningsId,
            UUID strikerId,
            UUID nonStrikerId,
            UUID bowlerId,
            String status
    ) {}
}
