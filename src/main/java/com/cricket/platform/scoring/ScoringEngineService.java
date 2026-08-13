package com.cricket.platform.scoring;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoringEngineService {
    private final ScoringEngine scoringEngine;
    private final EventFirstDeliveryService eventFirstDeliveryService;

    public ScoringEngineService(ScoringEngine scoringEngine,
                                EventFirstDeliveryService eventFirstDeliveryService) {
        this.scoringEngine = scoringEngine;
        this.eventFirstDeliveryService = eventFirstDeliveryService;
    }

    @Transactional
    public ScoringEngine.Result record(DeliveryCommand command,
                                      int currentLegalBalls,
                                      int currentRuns,
                                      int currentWickets,
                                      int maxWickets,
                                      Integer maxOvers) {
        ScoringEngine.Result result = scoringEngine.calculate(
                command, currentLegalBalls, currentRuns, currentWickets,
                maxWickets, maxOvers
        );

        eventFirstDeliveryService.record(
                command,
                result.over().overNumber(),
                result.over().ballNumber(),
                result.outcome().legalDelivery()
        );

        return result;
    }
}
