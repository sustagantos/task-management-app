package com.patobytes.tasks.web;

import com.patobytes.tasks.user.AppUser;
import com.patobytes.tasks.user.CurrentUserService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the whole authentication path end to end: Entra sign-in, session,
 * provisioning, and the owner id every later query will be scoped by.
 */
@RestController
@RequestMapping("/api")
public class MeController {

    private final CurrentUserService currentUser;

    public MeController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    public record Me(UUID id, String email, String displayName) {}

    @GetMapping("/me")
    public Me me() {
        AppUser user = currentUser.require();
        return new Me(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
