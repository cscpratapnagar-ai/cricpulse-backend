package com.cricket.platform.identity;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class IdentityController {
    private final RegisterUser registerUser;

    public IdentityController(RegisterUser registerUser) {
        this.registerUser = registerUser;
    }

    @PostMapping
    public RegisterUser.UserResponse register(@Valid @RequestBody RegisterUser.Request request) {
        return registerUser.execute(request);
    }
}
