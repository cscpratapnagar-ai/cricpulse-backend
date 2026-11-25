package com.cricket.platform.payment;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentControllerTest {
    private final PaymentController controller = new PaymentController(null, null, "");

    @Test
    void exposesLockedIndiaLaunchCatalogueWithOneTimeValidity() {
        var packages = controller.packages();
        Map<String, PaymentController.PackageDefinition> byCode = packages.stream()
                .collect(Collectors.toMap(PaymentController.PackageDefinition::code, pack -> pack));

        assertEquals(6, byCode.size());
        assertEquals(0L, byCode.get("FREE_TOURNAMENT").amountPaise());
        assertEquals(9900L, byCode.get("ORGANIZER_PLUS").amountPaise());
        assertEquals(19900L, byCode.get("ORGANIZER_PRO").amountPaise());
        assertEquals(14900L, byCode.get("BROADCAST_ADDON").amountPaise());
        assertEquals(39900L, byCode.get("CLUB_ACADEMY_PLUS").amountPaise());
        assertEquals(79900L, byCode.get("ACADEMY_BRANDING").amountPaise());

        assertEquals("Free", byCode.get("FREE_TOURNAMENT").validity());
        assertEquals("Per tournament", byCode.get("ORGANIZER_PLUS").validity());
        assertEquals("Per tournament", byCode.get("ORGANIZER_PRO").validity());
        assertEquals("Per tournament", byCode.get("BROADCAST_ADDON").validity());
        assertEquals("1 year", byCode.get("CLUB_ACADEMY_PLUS").validity());
        assertEquals("One-time setup", byCode.get("ACADEMY_BRANDING").validity());
        assertTrue(packages.stream().allMatch(pack -> "INR".equals(pack.currency())));
    }
}
