package com.cricket.platform.team.service.impl;

import com.cricket.platform.team.CreateTeam;
import com.cricket.platform.team.GetTeam;
import com.cricket.platform.team.service.TeamService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TeamServiceImpl implements TeamService {

    private final CreateTeam createTeam;
    private final GetTeam getTeam;

    public TeamServiceImpl(CreateTeam createTeam, GetTeam getTeam) {
        this.createTeam = createTeam;
        this.getTeam = getTeam;
    }

    @Override
    public CreateTeam.TeamResponse create(CreateTeam.Request request, String authenticatedEmail) {
        return createTeam.execute(request, authenticatedEmail);
    }

    @Override
    public GetTeam.TeamView getById(UUID teamId) {
        return getTeam.execute(teamId);
    }
}
