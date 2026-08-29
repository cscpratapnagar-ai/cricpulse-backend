package com.cricket.platform.identity;

import com.cricket.platform.identity.dto.RegisterRequest;
import com.cricket.platform.identity.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class IdentityController {

    private final RegisterUser registerUser;

    public IdentityController(RegisterUser registerUser) {
        this.registerUser = registerUser;
    }

    @PostMapping
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return registerUser.execute(request);
    }
}
