package com.cricket.platform.payment;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Resolves paid capabilities at tournament scope. Entitlements are granted only
 * after payment/admin activation and are revoked/expired centrally.
 */
@Component
public class TournamentEntitlementAccess {
    private final JdbcTemplate jdbc;

    public TournamentEntitlementAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM user_entitlements
                    WHERE tournament_id = ?
                      AND package_code = ANY(?)
                      AND revoked_at IS NULL
                      AND (expires_at IS NULL OR expires_at > now())
                )
                """, Boolean.class, tournamentId, jdbcArray(packageCodes));
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

    private java.sql.Array jdbcArray(String[] values) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<java.sql.Array>) connection ->
                connection.createArrayOf("varchar", values));
    }
}
