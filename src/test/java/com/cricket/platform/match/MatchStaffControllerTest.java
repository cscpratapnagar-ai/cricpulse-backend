package com.cricket.platform.match;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.http.HttpStatus;

class MatchStaffControllerTest {
    private final MatchStaffController controller = new MatchStaffController(null);

    @Test
    void normalizesSupportedMatchStaffRoles() {
        assertEquals("SCORER", controller.normalizeRole("scorer"));
        assertEquals("UMPIRE", controller.normalizeRole(" Umpire "));
        assertEquals("COMMENTATOR", controller.normalizeRole("commentator"));
        assertEquals("STREAMER", controller.normalizeRole("streamer"));
    }

    @Test
    void rejectsUnsupportedMatchStaffRoles() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.normalizeRole("ADMIN"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Unsupported"));
    }
}
