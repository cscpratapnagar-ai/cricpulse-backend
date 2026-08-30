package com.cricket.platform.player.service.impl;

import com.cricket.platform.player.GetPlayerStatistics;
import com.cricket.platform.player.service.PlayerStatisticsService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PlayerStatisticsServiceImpl implements PlayerStatisticsService {

    private final GetPlayerStatistics statistics;

    public PlayerStatisticsServiceImpl(GetPlayerStatistics statistics) {
        this.statistics = statistics;
    }

    @Override
    public List<GetPlayerStatistics.PlayerStatistics> getAll() {
        return statistics.all();
    }

    @Override
    public GetPlayerStatistics.PlayerStatistics getByPlayerId(UUID playerId) {
        return statistics.one(playerId);
    }
}
