package com.cricket.platform.payment;

import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PaymentController {
    private static final Map<String, PackageDefinition> PACKAGES = Map.of(
            "FREE_TOURNAMENT", new PackageDefinition("FREE_TOURNAMENT", "Free Tournament", 0L,
                    "INR", "Scoring, fixtures, registration and points table", "Free"),
            "ORGANIZER_PLUS", new PackageDefinition("ORGANIZER_PLUS", "Organizer Plus", 9900L,
                    "INR", "Additional tournament organizer tools", "Per tournament"),
            "ORGANIZER_PRO", new PackageDefinition("ORGANIZER_PRO", "Organizer Pro", 19900L,
                    "INR", "All Organizer Plus features plus Pro sponsor placement", "Per tournament"),
            "BROADCAST_ADDON", new PackageDefinition("BROADCAST_ADDON", "Broadcast Add-on", 14900L,
                    "INR", "Live broadcast tools; may be combined with Organizer Pro", "Per tournament"),
            "CLUB_ACADEMY_PLUS", new PackageDefinition("CLUB_ACADEMY_PLUS", "Club / Academy Plus", 39900L,
                    "INR", "Club and academy workspace access", "1 year"),
            "ACADEMY_BRANDING", new PackageDefinition("ACADEMY_BRANDING", "Academy Branding", 79900L,
                    "INR", "One-time academy branding setup", "One-time setup")
    );

    private final JdbcTemplate jdbc;
    private final JsonMapper objectMapper;
    private final String webhookSecret;

    public PaymentController(JdbcTemplate jdbc, JsonMapper objectMapper,
                             @Value("${payments.webhook.secret:}") String webhookSecret) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @GetMapping("/payments/packages")
    public List<PackageDefinition> packages() {
        return List.copyOf(PACKAGES.values());
    }

    @PostMapping("/payments/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrderView createOrder(@Valid @RequestBody CreateOrderRequest request, Authentication auth) {
        UUID userId = authenticatedUserId(auth);
        PackageDefinition pack = PACKAGES.get(request.packageCode().trim().toUpperCase(Locale.ROOT));
        if (pack == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown package");
        if (pack.amountPaise() == 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Free Tournament is the default access tier; no payment order is required");
        }
        boolean tournamentPackage = isTournamentPackage(pack.code());
        boolean workspacePackage = isWorkspacePackage(pack.code());
        UUID tournamentId = request.tournamentId();
        UUID teamId = request.teamId();
        if (tournamentPackage) {
            if (tournamentId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a tournament for this package");
            }
            Integer owned = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM tournaments t
                    WHERE t.id=? AND (t.owner_id=? OR EXISTS (
                        SELECT 1 FROM tournament_staff ts
                        WHERE ts.tournament_id=t.id AND ts.user_id=? AND ts.role='MANAGER'
                    ))
                    """, Integer.class, tournamentId, userId, userId);
            if (owned == null || owned == 0) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not manage this tournament");
            }
        } else if (tournamentId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This package must not reference a tournament");
        }
        if (workspacePackage) {
            if (teamId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a team workspace for this package");
            }
            Integer managed = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM teams t
                    WHERE t.id=? AND (t.owner_id=? OR EXISTS (
                        SELECT 1 FROM team_members tm
                        JOIN players p ON p.id=tm.player_id
                        WHERE tm.team_id=t.id AND p.user_id=? AND tm.role='MANAGER'
                    ))
                    """, Integer.class, teamId, userId, userId);
            if (managed == null || managed == 0) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not manage this team workspace");
            }
        } else if (teamId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This package must not reference a team workspace");
        }

        String key = request.idempotencyKey().trim();
        List<OrderView> existing = jdbc.query("""
                SELECT id, tournament_id, team_id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE user_id=? AND idempotency_key=?
                """, (rs, row) -> orderView(rs), userId, key);
        if (!existing.isEmpty()) {
            OrderView prior = existing.getFirst();
            if (!prior.packageCode().equals(pack.code())
                    || !java.util.Objects.equals(prior.tournamentId(), tournamentId)
                    || !java.util.Objects.equals(prior.teamId(), teamId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for a different purchase");
            }
            return prior;
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_orders
                (id,user_id,tournament_id,team_id,package_code,amount_paise,currency,status,provider,idempotency_key)
                VALUES (?,?,?,?,?,?,?,'PENDING','MANUAL',?)
                """, id, userId, tournamentId, teamId, pack.code(), pack.amountPaise(), pack.currency(), key);
        return getOrder(id, userId);
    }

    @GetMapping("/payments/orders")
    public List<OrderView> myOrders(Authentication auth) {
        UUID userId = authenticatedUserId(auth);
        return jdbc.query("""
                SELECT id, tournament_id, team_id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE user_id=? ORDER BY created_at DESC LIMIT 100
                """, (rs, row) -> orderView(rs), userId);
    }

    @GetMapping("/payments/entitlements")
    public List<EntitlementView> myEntitlements(Authentication auth) {
        UUID userId = authenticatedUserId(auth);
        return jdbc.query("""
                SELECT tournament_id, team_id, package_code, activation_source, activated_at, expires_at
                FROM user_entitlements
                WHERE user_id=? AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY activated_at DESC
                """, (rs, row) -> new EntitlementView(rs.getObject("tournament_id", UUID.class),
                rs.getObject("team_id", UUID.class), rs.getString("package_code"), rs.getString("activation_source"),
                rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)), userId);
    }

    @PostMapping("/admin/payments/orders/{orderId}/activate")
    @Transactional
    public OrderView manuallyActivate(@PathVariable UUID orderId,
                                      @Valid @RequestBody ManualActivationRequest request,
                                      Authentication auth) {
        UUID adminId = authenticatedUserId(auth);
        Boolean isAdmin = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND role='ADMIN')",
                Boolean.class, adminId);
        if (!Boolean.TRUE.equals(isAdmin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator access required");
        }
        OrderView order = jdbc.queryForObject("""
                SELECT id, tournament_id, team_id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE id=? FOR UPDATE
                """, (rs, row) -> orderView(rs), orderId);
        if ("REFUNDED".equals(order.status()) || "CANCELLED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled or refunded orders cannot be activated");
        }
        UUID ownerId = jdbc.queryForObject("SELECT user_id FROM payment_orders WHERE id=?", UUID.class, orderId);
        activateEntitlement(ownerId, order.tournamentId(), order.teamId(), order.packageCode(), orderId, adminId, "MANUAL");
        jdbc.update("UPDATE payment_orders SET status='PAID', manual_note=?, updated_at=now(), paid_at=COALESCE(paid_at,now()) WHERE id=?",
                request.note() == null ? null : request.note().trim(), orderId);
        return getOrder(orderId, ownerId);
    }

    @PostMapping("/public/payments/webhooks/{provider}")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public Map<String, String> webhook(@PathVariable String provider,
                                       @RequestHeader(value = "X-Payment-Signature", required = false) String signature,
                                       @RequestBody byte[] rawBody) throws Exception {
        String normalizedProvider = provider.trim().toUpperCase(Locale.ROOT);
        if (!"HMAC".equals(normalizedProvider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Only the generic HMAC adapter is enabled; provider-specific adapters are not configured");
        }
        if (webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment webhook is not configured");
        }
        if (!validSignature(rawBody, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid payment signature");
        }
        WebhookEvent event = objectMapper.readValue(rawBody, WebhookEvent.class);
        if (event.eventId() == null || event.eventId().isBlank() || event.orderId() == null
                || event.status() == null || event.eventType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incomplete payment event");
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        int inserted = jdbc.update("""
                INSERT INTO payment_webhook_events(provider,event_id,event_type,payload_hash)
                VALUES (?,?,?,?) ON CONFLICT (provider,event_id) DO NOTHING
                """, normalizedProvider, event.eventId(), event.eventType(), hash);
        if (inserted == 0) return Map.of("status", "duplicate");

        UUID orderId;
        try { orderId = UUID.fromString(event.orderId()); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order ID");
        }
        PaymentOrderRow order = jdbc.queryForObject("""
                SELECT id,user_id,tournament_id,team_id,package_code,status FROM payment_orders WHERE id=? FOR UPDATE
                """, (rs, row) -> new PaymentOrderRow(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("tournament_id", UUID.class),
                rs.getObject("team_id", UUID.class), rs.getString("package_code"), rs.getString("status")), orderId);
        if ("PAYMENT_SUCCESS".equalsIgnoreCase(event.eventType()) && "PAID".equalsIgnoreCase(event.status())) {
            if ("PENDING".equals(order.status())) {
                jdbc.update("""
                        UPDATE payment_orders SET status='PAID', provider=?, provider_payment_id=?,
                            paid_at=now(), updated_at=now() WHERE id=?
                        """, normalizedProvider, event.paymentId(), orderId);
                activateEntitlement(order.userId(), order.tournamentId(), order.teamId(), order.packageCode(), orderId, null, "PAYMENT");
            } else if (!"PAID".equals(order.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not payable in its current state");
            }
        } else if ("PAYMENT_FAILED".equalsIgnoreCase(event.eventType())
                && "FAILED".equalsIgnoreCase(event.status()) && "PENDING".equals(order.status())) {
            jdbc.update("UPDATE payment_orders SET status='FAILED', provider=?, updated_at=now() WHERE id=?",
                    normalizedProvider, orderId);
        } else if ("REFUND_SUCCESS".equalsIgnoreCase(event.eventType())
                && "REFUNDED".equalsIgnoreCase(event.status()) && "PAID".equals(order.status())) {
            jdbc.update("UPDATE payment_orders SET status='REFUNDED', updated_at=now() WHERE id=?", orderId);
            jdbc.update("UPDATE user_entitlements SET revoked_at=now() WHERE source_order_id=?", orderId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment event transition");
        }
        return Map.of("status", "processed");
    }

    private boolean validSignature(byte[] body, String supplied) throws Exception {
        if (supplied == null || supplied.isBlank()) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(body);
        byte[] actual;
        try { actual = HexFormat.of().parseHex(supplied.trim()); }
        catch (IllegalArgumentException ex) { return false; }
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean isTournamentPackage(String packageCode) {
        return switch (packageCode) {
            case "ORGANIZER_PLUS", "ORGANIZER_PRO", "BROADCAST_ADDON" -> true;
            default -> false;
        };
    }

    private boolean isWorkspacePackage(String packageCode) {
        return "CLUB_ACADEMY_PLUS".equals(packageCode) || "ACADEMY_BRANDING".equals(packageCode);
    }

    private void activateEntitlement(UUID userId, UUID tournamentId, UUID teamId, String packageCode,
                                     UUID orderId, UUID adminId, String source) {
        jdbc.update("""
                INSERT INTO user_entitlements(user_id,tournament_id,team_id,package_code,source_order_id,
                    activation_source,activated_by,expires_at)
                VALUES (?,?,?,?,?,?,?, CASE WHEN ?='CLUB_ACADEMY_PLUS' THEN now() + interval '1 year' ELSE NULL END)
                ON CONFLICT (source_order_id) DO NOTHING
                """, userId, tournamentId, teamId, packageCode, orderId, source, adminId, packageCode);
    }

    private UUID authenticatedUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return jdbc.queryForObject("SELECT id FROM users WHERE lower(email)=lower(?) OR id::text=? LIMIT 1",
                    UUID.class, auth.getName(), auth.getName());
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found");
        }
    }

    private OrderView getOrder(UUID id, UUID userId) {
        return jdbc.queryForObject("""
                SELECT id, tournament_id, team_id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE id=? AND user_id=?
                """, (rs, row) -> orderView(rs), id, userId);
    }

    private OrderView orderView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderView(rs.getObject("id", UUID.class), rs.getObject("tournament_id", UUID.class),
                rs.getObject("team_id", UUID.class), rs.getString("package_code"), rs.getLong("amount_paise"), rs.getString("currency"),
                rs.getString("status"), rs.getString("provider"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public record PackageDefinition(String code, String name, long amountPaise, String currency,
                                    String description, String validity) {}
    public record CreateOrderRequest(@NotBlank String packageCode,
                                     @NotBlank @Size(max = 120) String idempotencyKey,
                                     UUID tournamentId, UUID teamId) {}
    public record ManualActivationRequest(@NotBlank @Size(max = 500) String note) {}
    public record OrderView(UUID id, UUID tournamentId, UUID teamId, String packageCode, long amountPaise,
                            String currency, String status, String provider, OffsetDateTime createdAt) {}
    public record EntitlementView(UUID tournamentId, UUID teamId, String packageCode, String activationSource,
                                  OffsetDateTime activatedAt, OffsetDateTime expiresAt) {}
    public record WebhookEvent(String eventId, String eventType, String orderId,
                               String status, String paymentId) {}
    private record PaymentOrderRow(UUID id, UUID userId, UUID tournamentId, UUID teamId, String packageCode, String status) {}
}
