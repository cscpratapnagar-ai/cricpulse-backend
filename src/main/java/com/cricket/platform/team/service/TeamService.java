package com.cricket.platform.team.service;

import com.cricket.platform.team.CreateTeam;
import com.cricket.platform.team.GetTeam;

import java.util.UUID;

public interface TeamService {
    CreateTeam.TeamResponse create(CreateTeam.Request request, String authenticatedEmail);
    GetTeam.TeamView getById(UUID teamId);
}
