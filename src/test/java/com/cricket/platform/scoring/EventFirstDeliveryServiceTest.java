package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EventFirstDeliveryServiceTest {

    @Test
    void duplicateCommandReturnsPersistedEventWithoutAllocatingAnotherVersion() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        RecordDeliveryEvent recorder = mock(RecordDeliveryEvent.class);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, recorder);
        DeliveryCommand command = command(UUID.randomUUID(), UUID.randomUUID());
        DeliveryEvent existing = event(command, 4L, 4);
        when(repository.findByCommandId(command.commandId())).thenReturn(existing);

        EventFirstDeliveryService.Result result = service.record(command, 0, 4, true);

        assertSame(existing, result.event());
        assertFalse(result.created());
        verify(repository).lockInnings(command.inningsId());
        verify(repository).findByCommandId(command.commandId());
        verify(repository, never()).nextSequence(any());
        verify(repository, never()).nextVersion(any());
        verifyNoRecordExecution(recorder);
    }

    @Test
    void reusedCommandIdFromAnotherInningsIsRejected() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        RecordDeliveryEvent recorder = mock(RecordDeliveryEvent.class);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, recorder);
        UUID commandId = UUID.randomUUID();
        DeliveryCommand original = command(commandId, UUID.randomUUID());
        DeliveryCommand replay = command(commandId, UUID.randomUUID());
        when(repository.findByCommandId(commandId)).thenReturn(event(original, 4L, 4));

        assertThrows(IllegalArgumentException.class, () -> service.record(replay, 0, 1, true));
        verify(repository, never()).nextSequence(any());
        verify(repository, never()).nextVersion(any());
        verifyNoRecordExecution(recorder);
    }

    @Test
    void reusedCommandIdWithDifferentRunsIsRejected() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, mock(RecordDeliveryEvent.class));
        DeliveryCommand original = command(UUID.randomUUID(), UUID.randomUUID());
        DeliveryCommand altered = copy(original, 4, original.newBatterId());
        when(repository.findByCommandId(original.commandId())).thenReturn(event(original, 4L, 4));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.record(altered, 0, 4, true));
        assertTrue(error.getMessage().contains("different delivery payload"));
    }

    @Test
    void reusedCommandIdWithDifferentReplacementBatterIsRejected() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, mock(RecordDeliveryEvent.class));
        DeliveryCommand original = command(UUID.randomUUID(), UUID.randomUUID());
        DeliveryCommand wicketCommand = new DeliveryCommand(
                original.commandId(), original.inningsId(), original.strikerId(), original.nonStrikerId(),
                original.bowlerId(), original.batRuns(), original.extraRuns(), original.extraType(),
                "CAUGHT", original.strikerId(), UUID.randomUUID(), original.wicketFielderId(), original.recordedBy());
        DeliveryCommand altered = new DeliveryCommand(
                wicketCommand.commandId(), wicketCommand.inningsId(), wicketCommand.strikerId(),
                wicketCommand.nonStrikerId(), wicketCommand.bowlerId(), wicketCommand.batRuns(),
                wicketCommand.extraRuns(), wicketCommand.extraType(), wicketCommand.wicketType(),
                wicketCommand.dismissedPlayerId(), UUID.randomUUID(), wicketCommand.wicketFielderId(),
                wicketCommand.recordedBy());
        when(repository.findByCommandId(wicketCommand.commandId())).thenReturn(event(wicketCommand, 4L, 4));

        assertThrows(IllegalArgumentException.class, () -> service.record(altered, 0, 4, true));
    }

    @Test
    void legacyPersistedEventWithoutFingerprintRemainsRetryableWhenStoredFieldsMatch() {
        DeliveryEventRepository repository = mock(DeliveryEventRepository.class);
        DeliveryCommand command = command(UUID.randomUUID(), UUID.randomUUID());
        DeliveryEvent current = event(command, 4L, 4);
        DeliveryEvent legacy = new DeliveryEvent(
                current.eventId(), current.inningsId(), current.sequenceNo(), current.eventVersion(),
                current.eventType(), current.overNumber(), current.ballNumber(), current.strikerId(),
                current.nonStrikerId(), current.bowlerId(), current.batRuns(), current.extraRuns(),
                current.extraType(), current.wicketType(), current.dismissedPlayerId(),
                current.wicketFielderId(), current.legalDelivery(), "{}", current.commandId(),
                current.recordedBy(), current.createdAt());
        when(repository.findByCommandId(command.commandId())).thenReturn(legacy);
        EventFirstDeliveryService service = new EventFirstDeliveryService(repository, mock(RecordDeliveryEvent.class));

        EventFirstDeliveryService.Result result = service.record(command, 0, 4, true);

        assertSame(legacy, result.event());
        assertFalse(result.created());
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
        assertTrue(result.created());
        var order = inOrder(repository, recorder);
        order.verify(repository).lockInnings(inningsId);
        order.verify(repository).findByCommandId(command.commandId());
        order.verify(repository).nextSequence(inningsId);
        order.verify(repository).nextVersion(inningsId);
        order.verify(recorder).execute(command, 11L, 11, 1, 5, true);
    }

    private static void verifyNoRecordExecution(RecordDeliveryEvent recorder) {
        verify(recorder, never()).execute(any(), any(Long.TYPE), any(Integer.TYPE),
                any(Integer.TYPE), any(Integer.TYPE), any(Boolean.TYPE));
    }

    private static DeliveryCommand command(UUID commandId, UUID inningsId) {
        return new DeliveryCommand(commandId, inningsId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 0, null, null, null, null, UUID.randomUUID());
    }

    private static DeliveryCommand copy(DeliveryCommand source, int batRuns, UUID newBatterId) {
        return new DeliveryCommand(source.commandId(), source.inningsId(), source.strikerId(),
                source.nonStrikerId(), source.bowlerId(), batRuns, source.extraRuns(),
                source.extraType(), source.wicketType(), source.dismissedPlayerId(), newBatterId,
                source.wicketFielderId(), source.recordedBy());
    }

    private static DeliveryEvent event(DeliveryCommand command, long sequence, int version) {
        return DeliveryEventFactory.create(command, sequence, version, 0, 1, true);
    }
}
