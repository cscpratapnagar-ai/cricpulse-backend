package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventFirstDeliveryServiceTest {

    @Test
    void duplicateCommandReturnsPersistedEventWithoutAllocatingAnotherVersion() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        RecordDeliveryEvent recorder = mock(RecordDeliveryEvent.class);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, recorder);

        UUID commandId = UUID.randomUUID();
        UUID inningsId = UUID.randomUUID();
        DeliveryCommand command = command(commandId, inningsId);
        DeliveryEvent existing = event(command, 4L, 4);

        when(repository.findByCommandId(commandId)).thenReturn(existing);

        EventFirstDeliveryService.Result result = service.record(command, 0, 4, true);

        assertSame(existing, result.event());
        assertFalse(result.created());
        verify(repository).lockInnings(inningsId);
        verify(repository).findByCommandId(commandId);
        verify(repository, never()).nextSequence(any());
        verify(repository, never()).nextVersion(any());
        verifyNoRecordExecution(recorder);
    }

    @Test
    void freshCommandLocksBeforeCheckingIdempotencyAndAllocatesSequenceAndVersion() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        RecordDeliveryEvent recorder = mock(RecordDeliveryEvent.class);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, recorder);

        UUID inningsId = UUID.randomUUID();
        DeliveryCommand command = command(UUID.randomUUID(), inningsId);
        DeliveryEvent created = event(command, 11L, 11);

        when(repository.findByCommandId(command.commandId())).thenReturn(null);
        when(repository.nextSequence(inningsId)).thenReturn(11L);
        when(repository.nextVersion(inningsId)).thenReturn(11);
        when(recorder.execute(eq(command), eq(11L), eq(11), eq(1), eq(5), eq(true)))
                .thenReturn(new RecordDeliveryEvent.Result(created, true));

        EventFirstDeliveryService.Result result = service.record(command, 1, 5, true);

        assertSame(created, result.event());
        assertEquals(true, result.created());

        var order = inOrder(repository, recorder);
        order.verify(repository).lockInnings(inningsId);
        order.verify(repository).findByCommandId(command.commandId());
        order.verify(repository).nextSequence(inningsId);
        order.verify(repository).nextVersion(inningsId);
        order.verify(recorder).execute(command, 11L, 11, 1, 5, true);
    }

    private static void verifyNoRecordExecution(RecordDeliveryEvent recorder) {
        verify(recorder, never()).execute(any(), any(Long.TYPE), any(Integer.TYPE), any(Integer.TYPE), any(Integer.TYPE), any(Boolean.TYPE));
    }

    private static DeliveryCommand command(UUID commandId, UUID inningsId) {
        return new DeliveryCommand(
                commandId, inningsId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 0, null, null, null, null, UUID.randomUUID());
    }

    private static DeliveryEvent event(DeliveryCommand command, long sequence, int version) {
        return new DeliveryEvent(
                UUID.randomUUID(), command.inningsId(), sequence, version, "DELIVERY_RECORDED",
                0, 1, command.strikerId(), command.nonStrikerId(), command.bowlerId(),
                command.batRuns(), command.extraRuns(), command.extraType(), command.wicketType(),
                command.dismissedPlayerId(), true, "{}", command.commandId(), command.recordedBy(),
                java.time.OffsetDateTime.now());
    }
}
