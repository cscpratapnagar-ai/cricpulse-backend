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
    public DeliveryEvent execute(DeliveryCommand command, long sequenceNo, int eventVersion,
                                 int overNumber, int ballNumber, boolean legalDelivery) {
        if (repository.commandExists(command.commandId())) {
            throw new IllegalArgumentException("Delivery command has already been recorded");
        }

        DeliveryEvent event = DeliveryEventFactory.create(
                command, sequenceNo, eventVersion, overNumber, ballNumber, legalDelivery
        );
        repository.save(event);
        return event;
    }
}
