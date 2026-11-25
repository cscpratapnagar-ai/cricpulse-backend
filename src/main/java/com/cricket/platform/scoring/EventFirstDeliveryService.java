package com.cricket.platform.scoring;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventFirstDeliveryService {
    private final DeliveryEventRepository eventRepository;
    private final RecordDeliveryEvent recordDeliveryEvent;

    public EventFirstDeliveryService(DeliveryEventRepository eventRepository,
                                     RecordDeliveryEvent recordDeliveryEvent) {
        this.eventRepository = eventRepository;
        this.recordDeliveryEvent = recordDeliveryEvent;
    }

    @Transactional
    public Result findExisting(DeliveryCommand command) {
        eventRepository.lockInnings(command.inningsId());
        DeliveryEvent existing = eventRepository.findByCommandId(command.commandId());
        if (existing == null) {
            return null;
        }
        validateReplay(command, existing);
        return new Result(existing, false);
    }

    @Transactional
    public Result record(DeliveryCommand command,
                         int overNumber,
                         int ballNumber,
                         boolean legalDelivery) {
        // Serialize the complete allocation/projection path for this innings.
        eventRepository.lockInnings(command.inningsId());

        DeliveryEvent existing = eventRepository.findByCommandId(command.commandId());
        if (existing != null) {
            validateReplay(command, existing);
            return new Result(existing, false);
        }

        long sequenceNo = eventRepository.nextSequence(command.inningsId());
        int eventVersion = eventRepository.nextVersion(command.inningsId());
        RecordDeliveryEvent.Result result = recordDeliveryEvent.execute(
                command, sequenceNo, eventVersion, overNumber, ballNumber, legalDelivery
        );

        if (!result.created()) {
            validateReplay(command, result.event());
        }
        return new Result(result.event(), result.created());
    }

    private static void validateReplay(DeliveryCommand command, DeliveryEvent existing) {
        if (!command.inningsId().equals(existing.inningsId())) {
            throw new IllegalArgumentException(
                    "X-Command-Id has already been used for a different innings"
            );
        }
        if (!DeliveryEventFactory.matches(command, existing)) {
            throw new IllegalArgumentException(
                    "X-Command-Id has already been used with a different delivery payload"
            );
        }
    }

    public record Result(DeliveryEvent event, boolean created) {
    }
}
