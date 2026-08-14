package com.cricket.platform.team;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/teams")
public class TeamController {
    private final CreateTeam createTeam;
    private final GetTeam getTeam;
    private final JdbcTemplate jdbc;

    public TeamController(CreateTeam createTeam, GetTeam getTeam, JdbcTemplate jdbc) {
        this.createTeam = createTeam;
        this.getTeam = getTeam;
        this.jdbc = jdbc;
    }

    @PostMapping
    CreateTeam.TeamResponse create(
            @Valid @RequestBody CreateTeam.Request request,
            Authentication authentication
    ) {
        return createTeam.execute(request, authentication.getName());
    }

    @GetMapping("/{id}")
    GetTeam.TeamView get(@PathVariable UUID id) { return getTeam.execute(id); }

    @GetMapping
    List<GetTeam.TeamView> list() {
        return jdbc.query("SELECT id, name, city, owner_id FROM teams ORDER BY name",
                (rs, row) -> new GetTeam.TeamView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getObject("owner_id", UUID.class)
                ));
    }
}
