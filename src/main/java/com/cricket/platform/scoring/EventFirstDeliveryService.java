package com.cricket.platform.scoring;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
    public DeliveryEvent record(DeliveryCommand command,
                                int overNumber,
                                int ballNumber,
                                boolean legalDelivery) {
        if (eventRepository.commandExists(command.commandId())) {
            throw new IllegalArgumentException("Delivery command has already been recorded");
        }

        long sequenceNo = eventRepository.nextSequence(command.inningsId());
        int eventVersion = eventRepository.nextVersion(command.inningsId());

        return recordDeliveryEvent.execute(
                command,
                sequenceNo,
                eventVersion,
                overNumber,
                ballNumber,
                legalDelivery
        );
    }
}
