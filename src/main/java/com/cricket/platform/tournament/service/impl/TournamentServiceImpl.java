package com.cricket.platform.tournament.service.impl;

import com.cricket.platform.tournament.dto.request.CreateTournamentRequest;
import com.cricket.platform.tournament.dto.response.TournamentResponse;
import com.cricket.platform.tournament.service.TournamentService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TournamentServiceImpl implements TournamentService {

    private final JdbcTemplate jdbc;

    public TournamentServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TournamentResponse create(CreateTournamentRequest request, Authentication authentication) {
        UUID ownerId = ownerId(authentication);
        UUID tournamentId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tournaments(id,name,format,overs,location,start_date,status,owner_id) VALUES (?,?,?,?,?,?,?,?)",
                tournamentId, request.name().trim(), request.format().trim().toUpperCase(Locale.ROOT),
                request.overs(), blank(request.location()), request.startDate(), "DRAFT", ownerId);

        return findById(tournamentId, authentication);
    }

    @Override
    public List<TournamentResponse> findMine(Authentication authentication) {
        UUID ownerId = ownerId(authentication);
        return jdbc.query(
                "SELECT id,name,format,overs,location,start_date,status,created_at FROM tournaments WHERE owner_id=? ORDER BY created_at DESC",
                (row, index) -> response(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("format"), row.getInt("overs"), row.getString("location"),
                        row.getObject("start_date", LocalDate.class), row.getString("status"),
                        row.getObject("created_at").toString()),
                ownerId);
    }

    @Override
    public TournamentResponse findById(UUID tournamentId, Authentication authentication) {
        requireOwner(tournamentId, authentication);
        return jdbc.queryForObject(
                "SELECT id,name,format,overs,location,start_date,status,created_at FROM tournaments WHERE id=?",
                (row, index) -> response(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("format"), row.getInt("overs"), row.getString("location"),
                        row.getObject("start_date", LocalDate.class), row.getString("status"),
                        row.getObject("created_at").toString()),
                tournamentId);
    }

    private TournamentResponse response(UUID id, String name, String format, int overs, String location,
                                        LocalDate startDate, String status, String createdAt) {
        return new TournamentResponse(id, name, format, overs, location, startDate, status, createdAt);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UUID ownerId(Authentication authentication) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",
                    UUID.class, authentication.getName(), authentication.getName());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
    }

    private void requireOwner(UUID tournamentId, Authentication authentication) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class, tournamentId, ownerId(authentication));
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }
}
