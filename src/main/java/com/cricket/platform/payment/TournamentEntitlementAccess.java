package com.cricket.platform.payment;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class TournamentEntitlementAccess {
    private final JdbcTemplate jdbc;

    public TournamentEntitlementAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Organizer Pro includes every capability sold in Organizer Plus. */
    public boolean hasOrganizerPlus(UUID tournamentId) {
        return hasAnyPackage(tournamentId, "ORGANIZER_PLUS", "ORGANIZER_PRO");
    }

    public boolean hasOrganizerPro(UUID tournamentId) {
        return hasAnyPackage(tournamentId, "ORGANIZER_PRO");
    }

    public boolean hasBroadcastAddon(UUID tournamentId) {
        return hasAnyPackage(tournamentId, "BROADCAST_ADDON");
    }

    public void requireOrganizerProForMatch(UUID matchId) {
        requirePackageForMatch(matchId, "ORGANIZER_PRO",
                "Organizer Pro is required for sponsor placement and sponsor analytics.");
    }

    public void requireBroadcastAddonForMatch(UUID matchId) {
        requirePackageForMatch(matchId, "BROADCAST_ADDON",
                "Broadcast Add-on is required to use broadcast control.");
    }

    private boolean hasAnyPackage(UUID tournamentId, String... packageCodes) {
        if (tournamentId == null || packageCodes.length == 0) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(packageCodes.length, "?"));
        Object[] args = new Object[packageCodes.length + 1];
        args[0] = tournamentId;
        System.arraycopy(packageCodes, 0, args, 1, packageCodes.length);
        String sql = "SELECT EXISTS (SELECT 1 FROM user_entitlements WHERE tournament_id = ? " +
                "AND package_code IN (" + placeholders + ") AND revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > now()))";
        Boolean active = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(active);
    }

    private void requirePackageForMatch(UUID matchId, String packageCode, String message) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM tournament_matches tm
                    JOIN user_entitlements ue ON ue.tournament_id = tm.tournament_id
                    WHERE tm.match_id = ?
                      AND ue.package_code = ?
                      AND ue.revoked_at IS NULL
                      AND (ue.expires_at IS NULL OR ue.expires_at > now())
                )
                """, Boolean.class, matchId, packageCode);
        if (!Boolean.TRUE.equals(active)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }
}
