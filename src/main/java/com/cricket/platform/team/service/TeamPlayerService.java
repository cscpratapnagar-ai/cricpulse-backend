package com.cricket.platform.team.service;

import com.cricket.platform.team.BulkCreateTeamPlayers;
import org.springframework.security.core.Authentication;

import java.util.UUID;

public interface TeamPlayerService {
    BulkCreateTeamPlayers.Result createBulk(UUID teamId, BulkCreateTeamPlayers.Request request,
                                            Authentication authentication);
}
