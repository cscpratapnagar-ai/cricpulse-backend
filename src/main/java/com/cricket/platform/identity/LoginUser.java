package com.cricket.platform.identity;

import com.cricket.platform.identity.dto.AuthResponse;
import com.cricket.platform.identity.dto.LoginRequest;
import com.cricket.platform.identity.repository.IdentityRepository;
import com.cricket.platform.identity.repository.IdentityRepository.UserRecord;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class LoginUser {

    private final IdentityRepository identityRepository;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public LoginUser(IdentityRepository identityRepository, PasswordEncoder encoder, JwtService jwt) {
        this.identityRepository = identityRepository;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public AuthResponse execute(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        UserRecord user = identityRepository.findByEmailWithPassword(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!encoder.matches(request.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        return response(user, jwt.create(user.email(), user.role()));
    }

    public AuthResponse current(String email) {
        UserRecord user = identityRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        return response(user, null);
    }

    private AuthResponse response(UserRecord user, String accessToken) {
        return new AuthResponse(
                accessToken,
                user.id().toString(),
                user.fullName(),
                user.email(),
                user.role());
    }

    public static final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid email or password");
        }
    }
}
