package de.klackwerk.svenager.security

import de.klackwerk.svenager.SsoLoginException
import de.klackwerk.svenager.SsoLoginService
import de.klackwerk.svenager.User
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser

/**
 * Bridges the IdP identity onto a Svenager account: extracts the group
 * claim, resolves the role, provisions the user and rejects sign-ins with
 * no mapped role. authentication.name becomes the Svenager username.
 */
class SvenagerOidcUserService extends OidcUserService {

    private final String roleClaim
    private final SsoLoginService ssoLoginService

    SvenagerOidcUserService(String roleClaim, SsoLoginService ssoLoginService) {
        this.roleClaim = roleClaim
        this.ssoLoginService = ssoLoginService
    }

    @Override
    OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest)
        String nameKey = ['preferred_username', 'email', 'sub'].find { oidcUser.claims[it] }
        String username = oidcUser.claims[nameKey] as String
        try {
            User user = ssoLoginService.login(username, claimValues(oidcUser.claims[roleClaim]))
            return new DefaultOidcUser(
                    [new SimpleGrantedAuthority('ROLE_' + user.role.name())],
                    oidcUser.idToken, oidcUser.userInfo, nameKey)
        } catch (SsoLoginException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error('access_denied', e.message, null), e.message)
        }
    }

    /** The group claim may be a list, a single string or missing. */
    static List<String> claimValues(Object raw) {
        if (raw == null) {
            return []
        }
        raw instanceof Collection ? raw.collect { it?.toString() }.findAll() : [raw.toString()]
    }
}
