package com.cricket.platform.match;

import com.cricket.platform.scoring.ScoringAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
public class BroadcastSponsorController {
    private static final Set<String> TYPES = Set.of(
            "MAIN_SPONSOR", "OVER_SPONSOR", "PLAYER_OF_THE_MATCH", "BOUNDARY_SPONSOR", "CUSTOM");
    private static final Set<String> EVENTS = Set.of(
            "MATCH_START", "OVER_COMPLETE", "FOUR", "SIX", "WICKET",
            "PLAYER_OF_THE_MATCH", "MATCH_RESULT", "MANUAL");

    private final JdbcTemplate jdbc;
    private final ScoringAccess access;

    public BroadcastSponsorController(JdbcTemplate jdbc, ScoringAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/api/matches/{matchId}/sponsors")
    public List<SponsorView> list(@PathVariable UUID matchId, Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        return sponsors(matchId);
    }

    @PostMapping("/api/matches/{matchId}/sponsors")
    @Transactional
    public SponsorView create(@PathVariable UUID matchId, @Valid @RequestBody SponsorRequest request,
                              Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        String type = normalize(request.sponsorType());
        validateType(type);
        validateLogo(request.logoUrl());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broadcast_sponsors
                    (id, match_id, name, tagline, logo_url, sponsor_type, display_duration_seconds, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, matchId, request.name().trim(), blank(request.tagline()), blank(request.logoUrl()),
                type, request.displayDurationSeconds(), request.active() == null || request.active());
        return sponsor(matchId, id);
    }

    @PatchMapping("/api/matches/{matchId}/sponsors/{sponsorId}")
    @Transactional
    public SponsorView update(@PathVariable UUID matchId, @PathVariable UUID sponsorId,
                              @Valid @RequestBody SponsorRequest request, Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        requireSponsor(matchId, sponsorId);
        String type = normalize(request.sponsorType());
        validateType(type);
        validateLogo(request.logoUrl());
        jdbc.update("""
                UPDATE broadcast_sponsors
                SET name = ?, tagline = ?, logo_url = ?, sponsor_type = ?,
                    display_duration_seconds = ?, active = ?, updated_at = now()
                WHERE id = ? AND match_id = ?
                """, request.name().trim(), blank(request.tagline()), blank(request.logoUrl()), type,
                request.displayDurationSeconds(), request.active() == null || request.active(), sponsorId, matchId);
        return sponsor(matchId, sponsorId);
    }

    @DeleteMapping("/api/matches/{matchId}/sponsors/{sponsorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID matchId, @PathVariable UUID sponsorId, Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        requireSponsor(matchId, sponsorId);
        jdbc.update("DELETE FROM broadcast_sponsors WHERE id = ? AND match_id = ?", sponsorId, matchId);
    }

    @PutMapping("/api/matches/{matchId}/sponsors/{sponsorId}/placements")
    @Transactional
    public List<PlacementView> replacePlacements(@PathVariable UUID matchId, @PathVariable UUID sponsorId,
                                                  @Valid @RequestBody List<@Valid PlacementRequest> requests,
                                                  Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        requireSponsor(matchId, sponsorId);
        jdbc.update("DELETE FROM broadcast_sponsor_placements WHERE sponsor_id = ?", sponsorId);
        for (PlacementRequest request : requests) {
            String event = normalize(request.eventType());
            if (!EVENTS.contains(event)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sponsor event type: " + event);
            }
            jdbc.update("""
                    INSERT INTO broadcast_sponsor_placements (sponsor_id, event_type, enabled, priority)
                    VALUES (?, ?, ?, ?)
                    """, sponsorId, event, request.enabled() == null || request.enabled(), request.priority() == null ? 100 : request.priority());
        }
        return placements(sponsorId);
    }

    @GetMapping("/api/public/matches/{matchId}/sponsors")
    public List<PublicSponsorView> publicSponsors(@PathVariable UUID matchId,
                                                   @RequestParam(defaultValue = "MATCH_START") String eventType) {
        String event = normalize(eventType);
        if (!EVENTS.contains(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sponsor event type.");
        }
        return jdbc.query("""
                SELECT s.id, s.name, s.tagline, s.logo_url, s.sponsor_type,
                       s.display_duration_seconds, p.event_type
                FROM broadcast_sponsors s
                JOIN broadcast_sponsor_placements p ON p.sponsor_id = s.id
                WHERE s.match_id = ? AND s.active = TRUE AND p.enabled = TRUE AND p.event_type = ?
                ORDER BY p.priority, s.created_at
                """, (rs, row) -> new PublicSponsorView(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("tagline"),
                rs.getString("logo_url"), rs.getString("sponsor_type"),
                rs.getInt("display_duration_seconds"), rs.getString("event_type")), matchId, event);
    }

    @PostMapping("/api/matches/{matchId}/sponsors/{sponsorId}/impressions")
    @ResponseStatus(HttpStatus.CREATED)
    public ImpressionView recordImpression(@PathVariable UUID matchId, @PathVariable UUID sponsorId,
                                            @Valid @RequestBody ImpressionRequest request,
                                            Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        requireSponsor(matchId, sponsorId);
        String event = normalize(request.eventType());
        if (!EVENTS.contains(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sponsor event type.");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime displayedAt = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO broadcast_sponsor_impressions (id, sponsor_id, event_type, session_key, displayed_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, sponsorId, event, blank(request.sessionKey()), displayedAt);
        return new ImpressionView(id, sponsorId, event, displayedAt);
    }

    @GetMapping("/api/matches/{matchId}/sponsor-report")
    public List<SponsorReportRow> report(@PathVariable UUID matchId, Authentication authentication) {
        access.requireMatchManager(matchId, authentication);
        return jdbc.query("""
                SELECT s.id, s.name, s.sponsor_type, s.active,
                       COUNT(i.id) AS impressions,
                       MAX(i.displayed_at) AS last_displayed_at
                FROM broadcast_sponsors s
                LEFT JOIN broadcast_sponsor_impressions i ON i.sponsor_id = s.id
                WHERE s.match_id = ?
                GROUP BY s.id, s.name, s.sponsor_type, s.active
                ORDER BY impressions DESC, s.name
                """, (rs, row) -> new SponsorReportRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("sponsor_type"),
                rs.getBoolean("active"), rs.getLong("impressions"),
                rs.getObject("last_displayed_at", OffsetDateTime.class)), matchId);
    }

    private List<SponsorView> sponsors(UUID matchId) {
        return jdbc.query("""
                SELECT id, name, tagline, logo_url, sponsor_type, display_duration_seconds, active, created_at, updated_at
                FROM broadcast_sponsors WHERE match_id = ? ORDER BY created_at DESC
                """, (rs, row) -> new SponsorView(
                rs.getObject("id", UUID.class), rs.getObject("match_id", UUID.class),
                rs.getString("name"), rs.getString("tagline"), rs.getString("logo_url"),
                rs.getString("sponsor_type"), rs.getInt("display_duration_seconds"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                placements(rs.getObject("id", UUID.class))), matchId);
    }

    private SponsorView sponsor(UUID matchId, UUID sponsorId) {
        return jdbc.queryForObject("""
                SELECT id, name, tagline, logo_url, sponsor_type, display_duration_seconds, active, created_at, updated_at
                FROM broadcast_sponsors WHERE id = ? AND match_id = ?
                """, (rs, row) -> new SponsorView(
                rs.getObject("id", UUID.class), matchId, rs.getString("name"), rs.getString("tagline"),
                rs.getString("logo_url"), rs.getString("sponsor_type"), rs.getInt("display_duration_seconds"),
                rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), placements(sponsorId)), sponsorId, matchId);
    }

    private List<PlacementView> placements(UUID sponsorId) {
        return jdbc.query("""
                SELECT id, event_type, enabled, priority
                FROM broadcast_sponsor_placements WHERE sponsor_id = ? ORDER BY priority, event_type
                """, (rs, row) -> new PlacementView(rs.getObject("id", UUID.class),
                rs.getString("event_type"), rs.getBoolean("enabled"), rs.getInt("priority")), sponsorId);
    }

    private void requireSponsor(UUID matchId, UUID sponsorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM broadcast_sponsors WHERE id = ? AND match_id = ?",
                Integer.class, sponsorId, matchId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sponsor was not found for this match.");
        }
    }

    private void validateType(String type) {
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sponsor type.");
    }

    private void validateLogo(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) return;
        try {
            String scheme = URI.create(logoUrl.trim()).getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Logo URL must be a valid HTTP or HTTPS URL.");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record SponsorRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 180) String tagline,
            @Size(max = 2048) String logoUrl,
            @NotBlank String sponsorType,
            @NotNull @Min(5) @Max(30) Integer displayDurationSeconds,
            Boolean active) {}
    public record PlacementRequest(@NotBlank String eventType, Boolean enabled, Integer priority) {}
    public record ImpressionRequest(@NotBlank String eventType, @Size(max = 120) String sessionKey) {}
    public record SponsorView(UUID id, UUID matchId, String name, String tagline, String logoUrl,
                              String sponsorType, int displayDurationSeconds, boolean active,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt, List<PlacementView> placements) {}
    public record PlacementView(UUID id, String eventType, boolean enabled, int priority) {}
    public record PublicSponsorView(UUID id, String name, String tagline, String logoUrl,
                                    String sponsorType, int displayDurationSeconds, String eventType) {}
    public record ImpressionView(UUID id, UUID sponsorId, String eventType, OffsetDateTime displayedAt) {}
    public record SponsorReportRow(UUID sponsorId, String sponsorName, String sponsorType, boolean active,
                                   long impressions, OffsetDateTime lastDisplayedAt) {}
}
