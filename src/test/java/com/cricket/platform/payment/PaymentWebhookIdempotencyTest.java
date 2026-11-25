package com.cricket.platform.payment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentWebhookIdempotencyTest {
    @Test
    void acceptsAnExactWebhookEventReplay() {
        String hash = "a".repeat(64);

        assertDoesNotThrow(() -> PaymentController.ensureSameWebhookPayload(hash, hash));
    }

    @Test
    void rejectsAnEventIdReusedWithDifferentPayload() {
        String storedHash = "a".repeat(64);
        String incomingHash = "b".repeat(64);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> PaymentController.ensureSameWebhookPayload(storedHash, incomingHash));

        assertEquals(409, error.getStatusCode().value());
    }

    @Test
    void rejectsMissingStoredOrIncomingPayloadHash() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> PaymentController.ensureSameWebhookPayload(null, "a".repeat(64)));

        assertEquals(409, error.getStatusCode().value());
    }
}
