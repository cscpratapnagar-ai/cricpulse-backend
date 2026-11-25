package com.cricket.platform.tournament;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
public class TournamentRegistrationController {
    private final JdbcTemplate jdbc;

    public TournamentRegistrationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/public/tournaments/{tournamentId}/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RegistrationView submit(@PathVariable UUID tournamentId,
                                   @Valid @RequestBody RegistrationRequest request) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tournaments
                WHERE id = ? AND status IN ('DRAFT','ACTIVE')
                  AND registration_open = TRUE
                  AND (registration_deadline IS NULL OR registration_deadline > now())
                """, Integer.class, tournamentId);
        if (open == null || open == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM tournaments WHERE id=?", Integer.class, tournamentId);
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Team registration is closed or its deadline has passed");
        }
        Integer rosterCount = request.players().size();
        if (rosterCount < 2 || rosterCount > 30)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Roster must contain between 2 and 30 players");
        long uniqueJerseys = request.players().stream().map(PlayerRequest::jerseyNumber).distinct().count();
        if (uniqueJerseys != rosterCount)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jersey numbers must be unique");

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO tournament_team_registrations
                    (id,tournament_id,team_name,city,contact_name,contact_email,contact_phone)
                    VALUES (?,?,?,?,?,?,?)
                    """, id, tournamentId, request.teamName().trim(), blank(request.city()),
                    request.contactName().trim(), request.contactEmail().trim().toLowerCase(Locale.ROOT),
                    request.contactPhone().trim());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A registration for this team name already exists in this tournament");
        }
        for (PlayerRequest player : request.players()) {
            jdbc.update("""
                    INSERT INTO tournament_team_registration_players
                    (registration_id,full_name,jersey_number,batting_style,bowling_style)
                    VALUES (?,?,?,?,?)
                    """, id, player.fullName().trim(), player.jerseyNumber(),
                    blank(player.battingStyle()), blank(player.bowlingStyle()));
        }
        return getRegistration(id);
    }

    @GetMapping("/api/tournaments/{tournamentId}/registrations")
    public List<RegistrationView> list(@PathVariable UUID tournamentId, Authentication auth) {
        requireOrganizer(tournamentId, auth);
        List<RegistrationView> rows = jdbc.query("""
                SELECT id,tournament_id,team_name,city,contact_name,contact_email,contact_phone,
                       status,review_note,team_id,created_at,reviewed_at
                FROM tournament_team_registrations WHERE tournament_id=?
                ORDER BY CASE status WHEN 'PENDING' THEN 0 ELSE 1 END, created_at DESC
                """, (rs,n) -> map(rs), tournamentId);
        return rows.stream().map(row -> withPlayers(row)).toList();
    }

    @PatchMapping("/api/tournaments/{tournamentId}/registration")
    @Transactional
    public RegistrationSettings setRegistration(@PathVariable UUID tournamentId,
                                                  @Valid @RequestBody RegistrationSettingsRequest request,
                                                  Authentication auth) {
        requireOrganizer(tournamentId, auth);
        int changed = jdbc.update("UPDATE tournaments SET registration_open=?, registration_deadline=? WHERE id=?",
                request.open(), request.deadline(), tournamentId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        return new RegistrationSettings(tournamentId, request.open(), request.deadline());
    }

    @PatchMapping("/api/tournaments/{tournamentId}/registrations/{registrationId}")
    @Transactional
    public RegistrationView review(@PathVariable UUID tournamentId, @PathVariable UUID registrationId,
                                   @Valid @RequestBody ReviewRequest request, Authentication auth) {
        requireOrganizer(tournamentId, auth);
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("APPROVED", "REJECTED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be APPROVED or REJECTED");
        RegistrationView registration;
        try {
            registration = getRegistration(registrationId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found");
        }
        if (!registration.tournamentId().equals(tournamentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found");
        if (!"PENDING".equals(registration.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration has already been reviewed");

        UUID reviewer = jdbc.queryForObject("SELECT id FROM users WHERE lower(email)=lower(?) OR id::text=? LIMIT 1",
                UUID.class, auth.getName(), auth.getName());
        UUID teamId = null;
        if ("APPROVED".equals(status)) {
            teamId = UUID.randomUUID();
            jdbc.update("INSERT INTO teams(id,name,city,owner_id) VALUES (?,?,?,?)",
                    teamId, registration.teamName(), registration.city(), reviewer);
            jdbc.update("INSERT INTO tournament_teams(tournament_id,team_id,seed) VALUES (?,?,?)",
                    tournamentId, teamId, nextSeed(tournamentId));
        }
        jdbc.update("""
                UPDATE tournament_team_registrations
                SET status=?, review_note=?, team_id=?, reviewed_at=now(), reviewed_by=?
                WHERE id=? AND status='PENDING'
                """, status, blank(request.note()), teamId, reviewer, registrationId);
        return withPlayers(getRegistration(registrationId));
    }

    private int nextSeed(UUID tournamentId) {
        Integer seed = jdbc.queryForObject("SELECT COALESCE(MAX(seed),0)+1 FROM tournament_teams WHERE tournament_id=?",
                Integer.class, tournamentId);
        return seed == null ? 1 : seed;
    }

    private void requireOrganizer(UUID tournamentId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        Integer allowed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tournaments t JOIN users owner ON owner.id=t.owner_id
                WHERE t.id=? AND (
                    lower(owner.email)=lower(?) OR owner.id::text=? OR
                    EXISTS (SELECT 1 FROM tournament_staff ts WHERE ts.tournament_id=t.id
                            AND ts.user_id=(SELECT id FROM users WHERE lower(email)=lower(?) OR id::text=? LIMIT 1)
                            AND ts.role='MANAGER')
                )
                """, Integer.class, tournamentId, auth.getName(), auth.getName(), auth.getName(), auth.getName());
        if (allowed == null || allowed == 0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tournament organizer access required");
    }

    private RegistrationView getRegistration(UUID id) {
        return withPlayers(jdbc.queryForObject("""
                SELECT id,tournament_id,team_name,city,contact_name,contact_email,contact_phone,
                       status,review_note,team_id,created_at,reviewed_at
                FROM tournament_team_registrations WHERE id=?
                """, (rs,n) -> map(rs), id));
    }

    private RegistrationView withPlayers(RegistrationView row) {
        List<RegistrationPlayerView> players = jdbc.query("""
                SELECT full_name,jersey_number,batting_style,bowling_style
                FROM tournament_team_registration_players WHERE registration_id=? ORDER BY jersey_number NULLS LAST,full_name
                """, (rs,n) -> new RegistrationPlayerView(rs.getString("full_name"),
                (Integer) rs.getObject("jersey_number"), rs.getString("batting_style"),
                rs.getString("bowling_style")), row.id());
        return new RegistrationView(row.id(), row.tournamentId(), row.teamName(), row.city(),
                row.contactName(), row.contactEmail(), row.contactPhone(), row.status(), row.reviewNote(),
                row.teamId(), row.createdAt(), row.reviewedAt(), players);
    }

    private RegistrationView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RegistrationView(rs.getObject("id", UUID.class), rs.getObject("tournament_id", UUID.class),
                rs.getString("team_name"), rs.getString("city"), rs.getString("contact_name"),
                rs.getString("contact_email"), rs.getString("contact_phone"), rs.getString("status"),
                rs.getString("review_note"), rs.getObject("team_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("reviewed_at", OffsetDateTime.class),
                List.of());
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record RegistrationRequest(@NotBlank @Size(max=120) String teamName,
            @Size(max=120) String city, @NotBlank @Size(max=120) String contactName,
            @NotBlank @Email @Size(max=254) String contactEmail,
            @NotBlank @Pattern(regexp="^[+0-9() .-]{7,32}$") String contactPhone,
            @NotEmpty @Size(min=2,max=30) List<@Valid PlayerRequest> players) {}
    public record PlayerRequest(@NotBlank @Size(max=120) String fullName,
            @NotNull Integer jerseyNumber, @Size(max=24) String battingStyle, @Size(max=32) String bowlingStyle) {}
    public record ReviewRequest(@NotBlank String status, @Size(max=500) String note) {}
    public record RegistrationSettingsRequest(boolean open, OffsetDateTime deadline) {}
    public record RegistrationSettings(UUID tournamentId, boolean open, OffsetDateTime deadline) {}
    public record RegistrationPlayerView(String fullName, Integer jerseyNumber, String battingStyle, String bowlingStyle) {}
    public record RegistrationView(UUID id, UUID tournamentId, String teamName, String city,
            String contactName, String contactEmail, String contactPhone, String status, String reviewNote,
            UUID teamId, OffsetDateTime createdAt, OffsetDateTime reviewedAt, List<RegistrationPlayerView> players) {}
}
