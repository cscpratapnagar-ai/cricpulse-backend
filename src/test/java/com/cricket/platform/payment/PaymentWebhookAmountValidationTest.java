package com.cricket.platform.payment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentWebhookAmountValidationTest {
    @Test
    void acceptsExactAmountCurrencyAndPaymentReference() {
        assertDoesNotThrow(() -> PaymentController.validateSuccessfulPayment(
                19900L, "INR", 19900L, "inr", "pay_123"));
    }

    @Test
    void rejectsMissingOrDifferentAmount() {
        assertConflict(() -> PaymentController.validateSuccessfulPayment(
                19900L, "INR", null, "INR", "pay_123"));
        assertConflict(() -> PaymentController.validateSuccessfulPayment(
                19900L, "INR", 9900L, "INR", "pay_123"));
    }

    @Test
    void rejectsDifferentOrMissingCurrency() {
        assertConflict(() -> PaymentController.validateSuccessfulPayment(
                19900L, "INR", 19900L, "USD", "pay_123"));
        assertConflict(() -> PaymentController.validateSuccessfulPayment(
                19900L, "INR", 19900L, null, "pay_123"));
    }

    @Test
    void rejectsMissingPaymentReference() {
        assertConflict(() -> PaymentController.validateSuccessfulPayment(
                19900L, "INR", 19900L, "INR", " "));
    }

    private void assertConflict(org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action);
        assertEquals(409, error.getStatusCode().value());
    }
}
