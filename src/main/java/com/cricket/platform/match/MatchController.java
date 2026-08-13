package com.cricket.platform.match;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchController {
    private final CreateMatch createMatch;
    private final GetMatch getMatch;
    private final JdbcTemplate jdbc;

    public MatchController(CreateMatch createMatch, GetMatch getMatch, JdbcTemplate jdbc) {
        this.createMatch = createMatch;
        this.getMatch = getMatch;
        this.jdbc = jdbc;
    }

    @PostMapping
    CreateMatch.MatchResponse create(@Valid @RequestBody CreateMatch.Request request) {
        return createMatch.execute(request);
    }

    @GetMapping("/{id}")
    GetMatch.MatchView get(@PathVariable UUID id) { return getMatch.execute(id); }

    @GetMapping
    List<GetMatch.MatchView> list() {
        return jdbc.query("""
                        SELECT m.id,
                               m.name,
                               m.team_a_id,
                               m.team_b_id,
                               ta.name AS team_a_name,
                               tb.name AS team_b_name,
                               m.format,
                               m.status,
                               m.scheduled_at
                        FROM matches m
                        JOIN teams ta ON ta.id = m.team_a_id
                        JOIN teams tb ON tb.id = m.team_b_id
                        ORDER BY m.scheduled_at NULLS LAST, m.created_at DESC
                        """,
                (rs, row) -> new GetMatch.MatchView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("team_a_id", UUID.class), rs.getObject("team_b_id", UUID.class),
                        rs.getString("team_a_name"), rs.getString("team_b_name"),
                        rs.getString("format"), rs.getString("status"), rs.getObject("scheduled_at", java.time.OffsetDateTime.class)));
    }
}
