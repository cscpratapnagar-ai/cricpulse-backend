package com.cricket.platform.tournament.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record ScheduleFixtureRequest(
        @NotNull OffsetDateTime scheduledAt
) {
}
