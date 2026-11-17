package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventFirstProjectionServiceTest {

    @Test
    void exactLegacyProjectionIsAcknowledgedWithoutReplayingOrMutatingScore() {
        EventFirstDeliveryService eventService = mock(EventFirstDeliveryService.class);
        RecordDelivery recordDelivery = mock(RecordDelivery.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EventFirstProjectionService service =
                new EventFirstProjectionService(eventService, recordDelivery, jdbc);

        UUID inningsId = UUID.randomUUID();
        DeliveryCommand command = new DeliveryCommand(
                UUID.randomUUID(), inningsId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                4, 0, null, null, null, null, null
        );

        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(inningsId);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);

        EventFirstProjectionService.Result result = service.record(command, 11, 2);

        assertFalse(result.created());
        assertNull(result.event());
        verify(jdbc).queryForObject(anyString(), eq(UUID.class), any(Object[].class));
        verify(jdbc).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
        verify(eventService, never()).record(any(), any(Integer.TYPE), any(Integer.TYPE), any(Boolean.TYPE));
        verify(recordDelivery, never()).execute(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void newDeliveryContinuesThroughEventFirstAndProjectionPath() {
        EventFirstDeliveryService eventService = mock(EventFirstDeliveryService.class);
        RecordDelivery recordDelivery = mock(RecordDelivery.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EventFirstProjectionService service =
                new EventFirstProjectionService(eventService, recordDelivery, jdbc);

        UUID inningsId = UUID.randomUUID();
        DeliveryCommand command = new DeliveryCommand(
                UUID.randomUUID(), inningsId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                4, 0, null, null, null, null, null
        );
        DeliveryEvent event = new DeliveryEvent(
                UUID.randomUUID(), inningsId, 1L, 1, "DELIVERY_RECORDED",
                11, 2, command.strikerId(), command.nonStrikerId(), command.bowlerId(),
                4, 0, null, null, null, true, "{}", command.commandId(), null,
                java.time.OffsetDateTime.now()
        );

        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(inningsId);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(false);
        when(eventService.record(command, 11, 2, true))
                .thenReturn(new EventFirstDeliveryService.Result(event, true));

        EventFirstProjectionService.Result result = service.record(command, 11, 2);

        assertFalse(result.created() == false);
        verify(eventService).record(command, 11, 2, true);
        verify(recordDelivery).execute(any(RecordDelivery.Request.class));
        verify(jdbc).update(anyString(), eq(inningsId));
    }
}
