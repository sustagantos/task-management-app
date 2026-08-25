package com.patobytes.tasks.user;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the local {@link AppUser} row on first successful sign-in.
 *
 * <p>There is no invitation flow and no admin screen: anyone the Entra app
 * registration admits gets an account, and their own private task list. That is
 * the whole of the multi-user model.
 */
@Service
public class UserProvisioningService extends OidcUserService {

    private final AppUserRepository users;

    public UserProvisioningService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(request);

        String oid = resolveOid(oidcUser);
        if (oid == null || oid.isBlank()) {
            // Without a stable object id there is nothing safe to key on, and
            // falling back to email would silently orphan tasks on a rename.
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("missing_oid", "Entra token carried no 'oid' claim", null));
        }

        // The email claim is optional in Entra unless configured; the UPN in
        // preferred_username is always present and is good enough to display.
        String email = firstNonBlank(
                oidcUser.getClaimAsString("email"),
                oidcUser.getClaimAsString("preferred_username"),
                oid);
        String displayName = firstNonBlank(oidcUser.getClaimAsString("name"), email);

        users.findByEntraOid(oid)
                .ifPresentOrElse(
                        existing -> existing.seen(email, displayName),
                        () -> users.save(new AppUser(oid, email, displayName)));

        return oidcUser;
    }

    /**
     * The Entra object id, from the ID token.
     *
     * <p>Deliberately not from the UserInfo endpoint: Microsoft's returns only
     * sub, name, given_name, family_name, email and picture. The merged claim
     * view is a fallback, so the identity key does not silently depend on
     * Spring's merge behaviour.
     */
    static String resolveOid(OidcUser user) {
        String fromIdToken = user.getIdToken().getClaimAsString("oid");
        if (fromIdToken != null && !fromIdToken.isBlank()) {
            return fromIdToken;
        }
        return user.getClaimAsString("oid");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "unknown";
    }
}
