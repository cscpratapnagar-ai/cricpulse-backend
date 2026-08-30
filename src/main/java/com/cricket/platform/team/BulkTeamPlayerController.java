package com.cricket.platform.team;

import com.cricket.platform.team.service.TeamPlayerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/teams")
public class BulkTeamPlayerController {

    private final TeamPlayerService teamPlayerService;

    public BulkTeamPlayerController(TeamPlayerService teamPlayerService) {
        this.teamPlayerService = teamPlayerService;
    }

    @PostMapping("/{teamId}/players/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    BulkCreateTeamPlayers.Result create(
            @PathVariable UUID teamId,
            @Valid @RequestBody BulkCreateTeamPlayers.Request request,
            Authentication authentication) {
        return teamPlayerService.createBulk(teamId, request, authentication);
    }
}
