package de.klackwerk.svenager.security

import de.klackwerk.svenager.SsoLoginService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository

/**
 * OIDC single sign-on, configured under svenager.sso (see docs/sso.md for
 * Authentik and Keycloak examples). OIDC discovery runs at startup, so the
 * issuer must be reachable while SSO is enabled.
 */
@Configuration
class SsoConfig {

    @Bean
    @ConditionalOnProperty(name = 'svenager.sso.enabled', havingValue = 'true')
    ClientRegistrationRepository clientRegistrationRepository(Environment env) {
        List<String> scopes = env.getProperty('svenager.sso.scopes', 'openid,profile,email')
                .tokenize(',')*.trim().findAll()
        ClientRegistration registration = ClientRegistrations
                .fromIssuerLocation(env.getRequiredProperty('svenager.sso.issuerUri'))
                .registrationId('oidc')
                .clientId(env.getRequiredProperty('svenager.sso.clientId'))
                .clientSecret(env.getProperty('svenager.sso.clientSecret', ''))
                .scope(scopes as String[])
                // Kept under /api so the SPA dev proxy and the TLS reverse
                // proxy route the callback without extra rules.
                .redirectUri('{baseUrl}/api/v1/auth/sso/callback/{registrationId}')
                .build()
        new InMemoryClientRegistrationRepository(registration)
    }

    @Bean
    @ConditionalOnProperty(name = 'svenager.sso.enabled', havingValue = 'true')
    SvenagerOidcUserService svenagerOidcUserService(Environment env, SsoLoginService ssoLoginService) {
        new SvenagerOidcUserService(env.getProperty('svenager.sso.roleClaim', 'groups'), ssoLoginService)
    }
}
