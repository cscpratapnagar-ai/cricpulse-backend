package com.cricket.platform.team.service.impl;

import com.cricket.platform.team.BulkCreateTeamPlayers;
import com.cricket.platform.team.service.TeamPlayerService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TeamPlayerServiceImpl implements TeamPlayerService {

    private final BulkCreateTeamPlayers bulkCreateTeamPlayers;

    public TeamPlayerServiceImpl(BulkCreateTeamPlayers bulkCreateTeamPlayers) {
        this.bulkCreateTeamPlayers = bulkCreateTeamPlayers;
    }

    @Override
    public BulkCreateTeamPlayers.Result createBulk(
            UUID teamId,
            BulkCreateTeamPlayers.Request request,
            Authentication authentication) {
        return bulkCreateTeamPlayers.execute(teamId, request, authentication);
    }
}
