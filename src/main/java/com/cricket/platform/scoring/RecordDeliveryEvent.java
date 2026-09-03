package com.cricket.platform.scoring;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RecordDeliveryEvent {
    private final DeliveryEventRepository repository;

    public RecordDeliveryEvent(DeliveryEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Result execute(DeliveryCommand command, long sequenceNo, int eventVersion,
                          int overNumber, int ballNumber, boolean legalDelivery) {
        DeliveryEvent event = DeliveryEventFactory.create(
                command, sequenceNo, eventVersion, overNumber, ballNumber, legalDelivery
        );

        if (repository.insertIfAbsent(event)) {
            return new Result(event, true);
        }

        DeliveryEvent existing = repository.findByCommandId(command.commandId());
        if (existing == null) {
            throw new IllegalStateException("Delivery command insert was skipped but no persisted event exists");
        }
        return new Result(existing, false);
    }

    public record Result(DeliveryEvent event, boolean created) {
    }
}
