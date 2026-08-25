package com.patobytes.tasks.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the signed-in {@link AppUser} for the current request.
 *
 * <p>Everything that touches task data goes through this. The owner id it
 * returns is the only thing standing between one person's list and another's,
 * so it deliberately throws rather than returning null or empty - a caller that
 * forgets to check cannot silently end up with an unscoped query.
 */
@Service
public class CurrentUserService {

    private final AppUserRepository users;

    public CurrentUserService(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public AppUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new IllegalStateException("No authenticated OIDC principal on this request");
        }

        // Same source as provisioning: the ID token, not the UserInfo response.
        String oid = oidcUser.getIdToken().getClaimAsString("oid");
        if (oid == null || oid.isBlank()) {
            oid = oidcUser.getClaimAsString("oid");
        }
        return users.findByEntraOid(oid)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no app_user row: " + oid));
    }
}
