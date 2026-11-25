package com.cricket.platform.scoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class DeliveryEventFactory {
    private DeliveryEventFactory() {}

    public static DeliveryEvent create(DeliveryCommand command, long sequenceNo, int eventVersion, int overNumber, int ballNumber, boolean legalDelivery) {
        String payload = "{\\"commandId\\":\\""+command.commandId()+"\\",\\"batRuns\\":"+command.batRuns()+",\\"extraRuns\\":"+command.extraRuns()+",\\"wicketFielderId\\":"+(command.wicketFielderId()==null?"null":"\\""+command.wicketFielderId()+"\\"")+",\\"commandFingerprint\\":\\""+fingerprint(command)+"\\"}";
        return new DeliveryEvent(
                UUID.randomUUID(), command.inningsId(), sequenceNo, eventVersion,
                "DELIVERY_RECORDED", overNumber, ballNumber,
                command.strikerId(), command.nonStrikerId(), command.bowlerId(),
                command.batRuns(), command.extraRuns(), command.extraType(),
                command.wicketType(), command.dismissedPlayerId(), command.wicketFielderId(), legalDelivery,
                payload, command.commandId(), command.recordedBy(), OffsetDateTime.now()
        );
    }

    static boolean matches(DeliveryCommand command, DeliveryEvent event) {
        if (!command.inningsId().equals(event.inningsId())
                || !command.strikerId().equals(event.strikerId())
                || !command.nonStrikerId().equals(event.nonStrikerId())
                || !command.bowlerId().equals(event.bowlerId())
                || command.batRuns() != event.batRuns()
                || command.extraRuns() != event.extraRuns()
                || !Objects.equals(command.extraType(), event.extraType())
                || !Objects.equals(command.wicketType(), event.wicketType())
                || !Objects.equals(command.dismissedPlayerId(), event.dismissedPlayerId())
                || !Objects.equals(command.wicketFielderId(), event.wicketFielderId())
                || !Objects.equals(command.recordedBy(), event.recordedBy())) {
            return false;
        }

        // Older events remain retryable using all command fields persisted in columns.
        String payload = event.eventPayload();
        if (payload == null || !payload.contains("\\"commandFingerprint\\"")) {
            return true;
        }
        return payload.contains("\\"commandFingerprint\\":\\""+fingerprint(command)+"\\"");
    }

    private static String fingerprint(DeliveryCommand command) {
        String canonical = String.join("|",
                value(command.commandId()), value(command.inningsId()),
                value(command.strikerId()), value(command.nonStrikerId()), value(command.bowlerId()),
                Integer.toString(command.batRuns()), Integer.toString(command.extraRuns()),
                value(command.extraType()), value(command.wicketType()),
                value(command.dismissedPlayerId()), value(command.newBatterId()),
                value(command.wicketFielderId()), value(command.recordedBy()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String value(Object value) {
        return value == null ? "<null>" : value.toString();
    }
}
