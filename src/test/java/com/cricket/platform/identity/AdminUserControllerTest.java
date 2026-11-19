package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserControllerTest {
    private final AdminUserController controller = new AdminUserController(null);

    @Test
    void normalizesSupportedPlatformRoles() {
        assertEquals("ADMIN", controller.normalizeRole(" admin "));
        assertEquals("PLAYER", controller.normalizeRole("player"));
        assertEquals("SCORER", controller.normalizeRole("Scorer"));
        assertEquals("UMPIRE", controller.normalizeRole("umpire"));
        assertEquals("COMMENTATOR", controller.normalizeRole("commentator"));
        assertEquals("STREAMER", controller.normalizeRole("streamer"));
    }

    @Test
    void rejectsUnsupportedPlatformRoles() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.normalizeRole("OWNER"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Unsupported"));
    }

    @Test
    void rejectsInvalidPaginationBeforeQueryingDatabase() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.list("", -1, 25));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsPageSizesAboveTheMaximumBeforeQueryingDatabase() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.list("", 0, 101));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
