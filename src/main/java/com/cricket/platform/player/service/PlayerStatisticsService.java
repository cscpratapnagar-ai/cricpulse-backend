package com.cricket.platform.player.service;

import com.cricket.platform.player.GetPlayerStatistics;

import java.util.List;
import java.util.UUID;

public interface PlayerStatisticsService {
    List<GetPlayerStatistics.PlayerStatistics> getAll();
    GetPlayerStatistics.PlayerStatistics getByPlayerId(UUID playerId);
}
