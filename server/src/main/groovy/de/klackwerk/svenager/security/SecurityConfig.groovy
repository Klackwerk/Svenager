package de.klackwerk.svenager.security

import de.klackwerk.svenager.AuditService
import de.klackwerk.svenager.User
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository

/**
 * Two independent filter chains:
 *  1. Agent API — stateless, authenticated by per-device bearer tokens;
 *     /api/v1/enroll is open (it validates an enrollment token itself).
 *  2. UI API — session-based with SPA-style CSRF (XSRF-TOKEN cookie).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    /** Present only when svenager.sso.enabled is true (see SsoConfig). */
    @Autowired(required = false)
    ClientRegistrationRepository clientRegistrationRepository

    @Autowired(required = false)
    SvenagerOidcUserService svenagerOidcUserService

    @Autowired
    AuditService auditService

    @Bean
    PasswordEncoder passwordEncoder() {
        PasswordEncoderFactories.createDelegatingPasswordEncoder()
    }

    @Bean
    UserDetailsService userDetailsService() {
        return new UserDetailsService() {
            @Override
            UserDetails loadUserByUsername(String username) {
                User.withTransaction {
                    User user = User.findByUsername(username)
                    if (user == null || !user.enabled) {
                        throw new UsernameNotFoundException('unknown user')
                    }
                    org.springframework.security.core.userdetails.User
                            .withUsername(user.username)
                            .password(user.passwordHash)
                            .roles(user.role.name())
                            .build()
                } as UserDetails
            }
        }
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        configuration.authenticationManager
    }

    /** The filter participates only in the agent chain, not the container-wide chain. */
    @Bean
    FilterRegistrationBean deviceTokenAuthFilterRegistration(DeviceTokenAuthFilter filter) {
        FilterRegistrationBean registration = new FilterRegistrationBean(filter)
        registration.enabled = false
        registration
    }

    @Bean
    @Order(1)
    SecurityFilterChain agentApiChain(HttpSecurity http, DeviceTokenAuthFilter deviceTokenAuthFilter) {
        http.securityMatcher('/api/v1/enroll', '/api/v1/enroll/**', '/api/v1/agent/**')
                .csrf { it.disable() }
                .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
                .addFilterBefore(deviceTokenAuthFilter, UsernamePasswordAuthenticationFilter)
                .authorizeHttpRequests { auth ->
                    auth.requestMatchers('/api/v1/enroll', '/api/v1/enroll/**').permitAll()
                            .anyRequest().hasRole('DEVICE')
                }
                .exceptionHandling { it.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        http.build()
    }

    @Bean
    @Order(2)
    SecurityFilterChain uiApiChain(HttpSecurity http) {
        http.csrf { csrf ->
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                }
                .authorizeHttpRequests { auth ->
                    auth.requestMatchers('/api/v1/auth/login').permitAll()
                            // SSO: option discovery + OAuth2 redirect endpoints.
                            .requestMatchers('/api/v1/auth/login-options', '/api/v1/auth/sso/**').permitAll()
                            // Kiosk devices render this without any session.
                            .requestMatchers('/kiosk-demo/**').permitAll()
                            // Agent distribution for the enrollment one-liner.
                            .requestMatchers('/install.sh', '/install/**').permitAll()
                            // Only admins may admit token-less devices,
                            // manage users, or mint enrollment tokens.
                            .requestMatchers(org.springframework.http.HttpMethod.POST,
                                    '/api/v1/enrollment-requests/**').hasRole('ADMIN')
                            .requestMatchers('/api/v1/users/**').hasRole('ADMIN')
                            .requestMatchers('/api/v1/notification-channels/**').hasRole('ADMIN')
                            .requestMatchers('/api/v1/sso-mappings/**').hasRole('ADMIN')
                            .requestMatchers('/api/v1/audit/**', '/api/v1/audit').hasRole('ADMIN')
                            .requestMatchers(org.springframework.http.HttpMethod.POST,
                                    '/api/v1/enrollment-tokens/**').hasRole('ADMIN')
                            .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                                    '/api/v1/enrollment-tokens/**').hasRole('ADMIN')
                            // Viewers are read-only: every other mutation
                            // needs at least the operator role.
                            .requestMatchers(org.springframework.http.HttpMethod.GET, '/api/**').authenticated()
                            .requestMatchers('/api/v1/auth/logout').authenticated()
                            .requestMatchers('/api/**').hasAnyRole('ADMIN', 'OPERATOR')
                            // Actuator: health stays public for probes; every
                            // other management endpoint needs ADMIN (and is
                            // not exposed anyway, see application.yml).
                            .requestMatchers('/actuator/health').permitAll()
                            .requestMatchers('/actuator/**').hasRole('ADMIN')
                            // everything else: Grails error views
                            .anyRequest().permitAll()
                }
                .exceptionHandling { it.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
                .logout { logout ->
                    logout.logoutUrl('/api/v1/auth/logout')
                            .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler())
                }
        if (clientRegistrationRepository != null) {
            http.oauth2Login { login ->
                login.clientRegistrationRepository(clientRegistrationRepository)
                        .authorizationEndpoint { it.baseUri('/api/v1/auth/sso') }
                        .redirectionEndpoint { it.baseUri('/api/v1/auth/sso/callback/*') }
                        .userInfoEndpoint { it.oidcUserService(svenagerOidcUserService) }
                        .successHandler({ request, response, authentication ->
                            auditService.recordAs(authentication.name, 'login', 'user',
                                    authentication.name, 'signed in via SSO')
                            response.sendRedirect('/')
                        } as AuthenticationSuccessHandler)
                        .failureHandler({ request, response, exception ->
                            auditService.recordAs(null, 'login-failed', 'user', null,
                                    "SSO sign-in failed: ${exception.message?.take(300)}")
                            response.sendRedirect('/?ssoError=' +
                                    URLEncoder.encode(exception.message?.take(300) ?: 'sign-in failed', 'UTF-8'))
                        } as AuthenticationFailureHandler)
            }
        }
        http.build()
    }
}
