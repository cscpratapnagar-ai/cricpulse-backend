package com.cricket.platform.identity;

import com.cricket.platform.identity.dto.RegisterRequest;
import com.cricket.platform.identity.dto.UserResponse;
import com.cricket.platform.identity.repository.IdentityRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class RegisterUser {

    private final IdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;

    public RegisterUser(IdentityRepository identityRepository, PasswordEncoder passwordEncoder) {
        this.identityRepository = identityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse execute(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String fullName = request.fullName().trim();
        String phone = request.phone() == null ? "" : request.phone().trim();

        validate(request, fullName, phone);

        if (identityRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }
        if (identityRepository.existsByPhone(phone)) {
            throw new PhoneAlreadyExistsException();
        }

        UUID id = UUID.randomUUID();
        String role = "PLAYER";

        try {
            identityRepository.createUser(
                    id, fullName, email, phone, role,
                    passwordEncoder.encode(request.password()));
            identityRepository.createPlayerProfile(id);
        } catch (DuplicateKeyException ex) {
            String message = ex.getMostSpecificCause() == null
                    ? "" : ex.getMostSpecificCause().getMessage();
            if (message != null && message.toLowerCase().contains("phone")) {
                throw new PhoneAlreadyExistsException();
            }
            throw new EmailAlreadyExistsException();
        }

        return new UserResponse(id, fullName, email, role);
    }

    private void validate(RegisterRequest request, String fullName, String phone) {
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
    }

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
