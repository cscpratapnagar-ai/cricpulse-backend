package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class UndoDelivery {
    private final JdbcTemplate jdbc;
    private final GetLiveScore getLiveScore;

    public UndoDelivery(JdbcTemplate jdbc, GetLiveScore getLiveScore) {
        this.jdbc = jdbc;
        this.getLiveScore = getLiveScore;
    }

    @Transactional
    public GetLiveScore.Score execute(UUID inningsId) {
        UUID deliveryId = jdbc.queryForObject(
                "SELECT id FROM deliveries WHERE innings_id = ? ORDER BY created_at DESC LIMIT 1",
                UUID.class, inningsId);
        jdbc.update("DELETE FROM deliveries WHERE id = ?", deliveryId);
        jdbc.update("""
                UPDATE innings SET
                    total_runs = COALESCE((SELECT SUM(bat_runs + extra_runs) FROM deliveries WHERE innings_id = ?), 0),
                    wickets = COALESCE((SELECT COUNT(*) FROM deliveries WHERE innings_id = ? AND wicket_type IS NOT NULL), 0),
                    legal_balls = COALESCE((SELECT COUNT(*) FROM deliveries WHERE innings_id = ? AND (extra_type IS NULL OR extra_type NOT IN ('WIDE', 'NO_BALL'))), 0)
                WHERE id = ?
                """, inningsId, inningsId, inningsId, inningsId);
        return getLiveScore.execute(inningsId);
    }
}
