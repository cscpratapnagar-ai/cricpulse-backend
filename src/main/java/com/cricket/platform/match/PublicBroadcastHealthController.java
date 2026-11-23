package com.cricket.platform.match;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Minimal public readiness probe for broadcast clients.
 *
 * This deliberately exposes only availability and a timestamp; database
 * details, exception messages and infrastructure metadata stay private.
 */
@RestController
@RequestMapping("/api/public")
public class PublicBroadcastHealthController {
    private final JdbcTemplate jdbc;

    public PublicBroadcastHealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/broadcast-health")
    public ResponseEntity<BroadcastHealth> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(new BroadcastHealth("UP", OffsetDateTime.now()));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BroadcastHealth("DOWN", OffsetDateTime.now()));
        }
    }

    public record BroadcastHealth(String status, OffsetDateTime checkedAt) {}
}
