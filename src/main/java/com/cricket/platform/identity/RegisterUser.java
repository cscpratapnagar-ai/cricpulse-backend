package com.cricket.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RegisterUser {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public RegisterUser(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse execute(Request request) {
        String email = request.email().trim().toLowerCase();
        String fullName = request.fullName().trim();
        String phone = request.phone() == null ? "" : request.phone().trim();

        if (request.password() == null || request.password().length() < 8) {
            throw new IllegalArgumentException("Password must contain at least 8 characters");
        }
        if (fullName.length() < 2) {
            throw new IllegalArgumentException("Full name must contain at least 2 characters");
        }
        if (phone.isBlank()) {
            throw new IllegalArgumentException("Mobile number is required");
        }
        if (!phone.matches("^\\+?[0-9][0-9 -]{8,14}$")) {
            throw new IllegalArgumentException("Please enter a valid mobile number");
        }

        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(?))", Boolean.class, email))) {
            throw new EmailAlreadyExistsException();
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE phone = ?)", Boolean.class, phone))) {
            throw new PhoneAlreadyExistsException();
        }

        UUID id = UUID.randomUUID();
        // Public registration can never self-assign a privileged role.
        // ADMIN, CAPTAIN and SCORER must be granted through protected flows.
        String role = "PLAYER";
        try {
            jdbc.update(
                    "INSERT INTO users(id, full_name, email, phone, role, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                    id, fullName, email, phone, role, passwordEncoder.encode(request.password()));
        } catch (DuplicateKeyException ex) {
            // Keep database uniqueness as the final race-condition guard.
            String message = ex.getMostSpecificCause() == null ? "" : ex.getMostSpecificCause().getMessage();
            if (message != null && message.toLowerCase().contains("phone")) {
                throw new PhoneAlreadyExistsException();
            }
            throw new EmailAlreadyExistsException();
        }

        return new UserResponse(id, fullName, email, role);
    }

    public record Request(@NotBlank String fullName, @Email @NotBlank String email,
                          @NotBlank String phone, String role, String password) {}

    public record UserResponse(UUID id, String fullName, String email, String role) {}

    public static final class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException() {
            super("An account with this email already exists. Please use another email or sign in.");
        }
    }

    public static final class PhoneAlreadyExistsException extends RuntimeException {
        public PhoneAlreadyExistsException() {
            super("An account with this mobile number already exists. Please use another mobile number.");
        }
    }
}
