package com.cricket.platform.payment;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentControllerTest {
    private final PaymentController controller = new PaymentController(null, null, "");

    @Test
    void exposesOnlyOneTimePackagesWithInrPricing() {
        var packages = controller.packages();

        assertEquals(Set.of("PRO_TOURNAMENT", "BROADCAST", "CLUB_ACADEMY"),
                packages.stream().map(PaymentController.PackageDefinition::code).collect(Collectors.toSet()));
        assertTrue(packages.stream().allMatch(pack -> "INR".equals(pack.currency())));
        assertTrue(packages.stream().allMatch(pack -> pack.amountPaise() > 0));
    }
}
