package com.cricket.platform.identity;

import com.cricket.platform.identity.dto.AuthResponse;
import com.cricket.platform.identity.dto.LoginRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginUser loginUser;

    public AuthController(LoginUser loginUser) {
        this.loginUser = loginUser;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return loginUser.execute(request);
    }

    @GetMapping("/me")
    public AuthResponse me(Authentication authentication) {
        return loginUser.current(authentication.getName());
    }
}
