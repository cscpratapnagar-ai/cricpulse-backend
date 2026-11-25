package com.cricket.platform.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            "PRO_TOURNAMENT", new PackageDefinition("PRO_TOURNAMENT", "Pro Tournament", 99900L,
                    "INR", "Tournament management tools for one event"),
            "BROADCAST", new PackageDefinition("BROADCAST", "Broadcast", 249900L,
                    "INR", "Live scoring and broadcast tools for one event"),
            "CLUB_ACADEMY", new PackageDefinition("CLUB_ACADEMY", "Club / Academy", 999900L,
                    "INR", "Club and academy workspace; manually renewed")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public PaymentController(JdbcTemplate jdbc, ObjectMapper objectMapper,
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
        String key = request.idempotencyKey().trim();
        List<OrderView> existing = jdbc.query("""
                SELECT id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE user_id=? AND idempotency_key=?
                """, (rs, row) -> orderView(rs), userId, key);
        if (!existing.isEmpty()) return existing.getFirst();

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_orders
                (id,user_id,package_code,amount_paise,currency,status,provider,idempotency_key)
                VALUES (?,?,?,?,?,'PENDING','MANUAL',?)
                """, id, userId, pack.code(), pack.amountPaise(), pack.currency(), key);
        return getOrder(id, userId);
    }

    @GetMapping("/payments/orders")
    public List<OrderView> myOrders(Authentication auth) {
        UUID userId = authenticatedUserId(auth);
        return jdbc.query("""
                SELECT id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE user_id=? ORDER BY created_at DESC LIMIT 100
                """, (rs, row) -> orderView(rs), userId);
    }

    @GetMapping("/payments/entitlements")
    public List<EntitlementView> myEntitlements(Authentication auth) {
        UUID userId = authenticatedUserId(auth);
        return jdbc.query("""
                SELECT package_code, activation_source, activated_at, expires_at
                FROM user_entitlements
                WHERE user_id=? AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY activated_at DESC
                """, (rs, row) -> new EntitlementView(rs.getString("package_code"),
                rs.getString("activation_source"), rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)), userId);
    }

    @PostMapping("/admin/payments/orders/{orderId}/activate")
    @Transactional
    public OrderView manuallyActivate(@PathVariable UUID orderId,
                                      @Valid @RequestBody ManualActivationRequest request,
                                      Authentication auth) {
        UUID adminId = authenticatedUserId(auth);
        Integer isAdmin = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='ADMIN' FOR UPDATE",
                Integer.class, adminId);
        if (isAdmin == null || isAdmin == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator access required");
        }
        OrderView order = jdbc.queryForObject("""
                SELECT id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE id=? FOR UPDATE
                """, (rs, row) -> orderView(rs), orderId);
        if ("REFUNDED".equals(order.status()) || "CANCELLED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled or refunded orders cannot be activated");
        }
        UUID ownerId = jdbc.queryForObject("SELECT user_id FROM payment_orders WHERE id=?", UUID.class, orderId);
        activateEntitlement(ownerId, order.packageCode(), orderId, adminId, "MANUAL");
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
                SELECT id,user_id,package_code,status FROM payment_orders WHERE id=? FOR UPDATE
                """, (rs, row) -> new PaymentOrderRow(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("package_code"), rs.getString("status")), orderId);
        if ("PAYMENT_SUCCESS".equalsIgnoreCase(event.eventType()) && "PAID".equalsIgnoreCase(event.status())) {
            if ("PENDING".equals(order.status())) {
                jdbc.update("""
                        UPDATE payment_orders SET status='PAID', provider=?, provider_payment_id=?,
                            paid_at=now(), updated_at=now() WHERE id=?
                        """, normalizedProvider, event.paymentId(), orderId);
                activateEntitlement(order.userId(), order.packageCode(), orderId, null, "PAYMENT");
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

    private void activateEntitlement(UUID userId, String packageCode, UUID orderId, UUID adminId, String source) {
        jdbc.update("""
                INSERT INTO user_entitlements(user_id,package_code,source_order_id,activation_source,activated_by,expires_at)
                VALUES (?,?,?,?,?, CASE WHEN ?='CLUB_ACADEMY' THEN now() + interval '1 year' ELSE NULL END)
                ON CONFLICT (source_order_id) DO NOTHING
                """, userId, packageCode, orderId, source, adminId, packageCode);
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
                SELECT id, package_code, amount_paise, currency, status, provider, created_at
                FROM payment_orders WHERE id=? AND user_id=?
                """, (rs, row) -> orderView(rs), id, userId);
    }

    private OrderView orderView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderView(rs.getObject("id", UUID.class), rs.getString("package_code"),
                rs.getLong("amount_paise"), rs.getString("currency"), rs.getString("status"),
                rs.getString("provider"), rs.getObject("created_at", OffsetDateTime.class));
    }

    public record PackageDefinition(String code, String name, long amountPaise, String currency, String description) {}
    public record CreateOrderRequest(@NotBlank String packageCode,
                                     @NotBlank @Size(max = 120) String idempotencyKey) {}
    public record ManualActivationRequest(@NotBlank @Size(max = 500) String note) {}
    public record OrderView(UUID id, String packageCode, long amountPaise, String currency,
                            String status, String provider, OffsetDateTime createdAt) {}
    public record EntitlementView(String packageCode, String activationSource,
                                  OffsetDateTime activatedAt, OffsetDateTime expiresAt) {}
    public record WebhookEvent(String eventId, String eventType, String orderId,
                               String status, String paymentId) {}
    private record PaymentOrderRow(UUID id, UUID userId, String packageCode, String status) {}
}
