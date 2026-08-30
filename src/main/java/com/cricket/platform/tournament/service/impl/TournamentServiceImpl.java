package com.cricket.platform.tournament.service.impl;

import com.cricket.platform.tournament.TournamentController;
import com.cricket.platform.tournament.service.TournamentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class TournamentServiceImpl implements TournamentService {

    private final JdbcTemplate jdbc;

    public TournamentServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TournamentController.TournamentView> findMine(Authentication authentication) {
        UUID ownerId = ownerId(authentication);
        return jdbc.query(
                "SELECT id,name,format,overs,location,start_date,status,created_at FROM tournaments WHERE owner_id=? ORDER BY created_at DESC",
                (row, index) -> view(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("format"), row.getInt("overs"), row.getString("location"),
                        row.getObject("start_date", LocalDate.class), row.getString("status"),
                        row.getObject("created_at").toString()),
                ownerId);
    }

    @Override
    public TournamentController.TournamentView findById(UUID tournamentId, Authentication authentication) {
        requireOwner(tournamentId, authentication);
        return jdbc.queryForObject(
                "SELECT id,name,format,overs,location,start_date,status,created_at FROM tournaments WHERE id=?",
                (row, index) -> view(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("format"), row.getInt("overs"), row.getString("location"),
                        row.getObject("start_date", LocalDate.class), row.getString("status"),
                        row.getObject("created_at").toString()),
                tournamentId);
    }

    private TournamentController.TournamentView view(UUID id, String name, String format, int overs,
                                                     String location, LocalDate startDate,
                                                     String status, String createdAt) {
        return new TournamentController.TournamentView(id, name, format, overs, location, startDate, status, createdAt);
    }

    private UUID ownerId(Authentication authentication) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",
                    UUID.class, authentication.getName(), authentication.getName());
        } catch (Exception exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "User not found");
        }
    }

    private void requireOwner(UUID tournamentId, Authentication authentication) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class, tournamentId, ownerId(authentication));
        if (count == null || count == 0) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }
}
