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
    public Result record(DeliveryCommand command,
                         int overNumber,
                         int ballNumber,
                         boolean legalDelivery) {
        DeliveryEvent existing = eventRepository.findByCommandId(command.commandId());
        if (existing != null) {
            return new Result(existing, false);
        }

        long sequenceNo = eventRepository.nextSequence(command.inningsId());
        int eventVersion = eventRepository.nextVersion(command.inningsId());
        RecordDeliveryEvent.Result result = recordDeliveryEvent.execute(
                command,
                sequenceNo,
                eventVersion,
                overNumber,
                ballNumber,
                legalDelivery
        );

        return new Result(result.event(), result.created());
    }

    public record Result(DeliveryEvent event, boolean created) {
    }
}
