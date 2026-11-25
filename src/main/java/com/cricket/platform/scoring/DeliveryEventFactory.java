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
        String payload = """
                {"commandId":"%s","batRuns":%d,"extraRuns":%d,"wicketFielderId":%s,"commandFingerprint":"%s"}
                """.formatted(command.commandId(), command.batRuns(), command.extraRuns(),
                command.wicketFielderId() == null ? "null" : "\"" + command.wicketFielderId() + "\"",
                fingerprint(command)).trim();
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
        if (payload == null || !payload.contains("\"commandFingerprint\"")) {
            return true;
        }
        return payload.contains("\"commandFingerprint\":\"" + fingerprint(command) + "\"");
    }

    private static String fingerprint(DeliveryCommand command) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, command.commandId());
        append(canonical, command.inningsId());
        append(canonical, command.strikerId());
        append(canonical, command.nonStrikerId());
        append(canonical, command.bowlerId());
        append(canonical, command.batRuns());
        append(canonical, command.extraRuns());
        append(canonical, command.extraType());
        append(canonical, command.wicketType());
        append(canonical, command.dismissedPlayerId());
        append(canonical, command.newBatterId());
        append(canonical, command.wicketFielderId());
        append(canonical, command.recordedBy());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        String text = value.toString();
        target.append(text.length()).append(':').append(text);
    }
}
