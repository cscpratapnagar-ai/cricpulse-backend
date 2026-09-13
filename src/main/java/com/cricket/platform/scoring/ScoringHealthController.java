package com.cricket.platform.scoring;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Small operational endpoint used by scoring/broadcast diagnostics. */
@RestController
@RequestMapping("/api/scoring/health")
public class ScoringHealthController {
    private final JdbcTemplate jdbc;
    private final ScoringAccess scoringAccess;

    public ScoringHealthController(JdbcTemplate jdbc, ScoringAccess scoringAccess) {
        this.jdbc = jdbc;
        this.scoringAccess = scoringAccess;
    }

    @GetMapping("/innings/{inningsId}")
    public ResponseEntity<Map<String, Object>> check(
            @PathVariable UUID inningsId,
            Authentication authentication) {
        UUID matchId = scoringAccess.matchIdForInnings(inningsId);
        scoringAccess.requireMatchManager(matchId, authentication);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inningsId", inningsId);
        result.put("matchId", matchId);
        result.put("eventCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_events WHERE innings_id = ?", Long.class, inningsId));
        result.put("deliveryCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE innings_id = ?", Long.class, inningsId));
        result.put("stateVersion", jdbc.queryForObject(
                "SELECT state_version FROM innings WHERE id = ?", Long.class, inningsId));
        result.put("consistent", jdbc.queryForObject("""
                SELECT COUNT(*) = (SELECT COUNT(*) FROM deliveries WHERE innings_id = ?)
                FROM delivery_events WHERE innings_id = ?
                """, Boolean.class, inningsId, inningsId));
        return ResponseEntity.ok(result);
    }
}
