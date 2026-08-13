package com.cricket.platform.identity;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final LoginUser loginUser;

    public AuthController(LoginUser loginUser) { this.loginUser = loginUser; }

    @PostMapping("/login")
    LoginUser.AuthResponse login(@Valid @RequestBody LoginUser.Request request) {
        return loginUser.execute(request);
    }

    @GetMapping("/me")
    LoginUser.AuthResponse me(Authentication authentication) {
        return loginUser.current(authentication.getName());
    }
}
