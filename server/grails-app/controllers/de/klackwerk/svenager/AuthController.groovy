package de.klackwerk.svenager

import grails.converters.JSON
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository

class AuthController {

    static allowedMethods = [login: 'POST', me: 'GET', loginOptions: 'GET']

    AuthenticationManager authenticationManager
    AuditService auditService
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository()

    def login() {
        def body = request.JSON
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body?.username as String, body?.password as String))
            // Prevent session fixation before persisting the new context.
            request.getSession(true)
            request.changeSessionId()
            SecurityContext context = SecurityContextHolder.createEmptyContext()
            context.authentication = auth
            SecurityContextHolder.context = context
            securityContextRepository.saveContext(context, request, response)
            auditService.recordAs(auth.name, 'login', 'user', auth.name, 'signed in')
            render(userInfo(auth) as JSON)
        } catch (AuthenticationException ignored) {
            auditService.recordAs(body?.username as String, 'login-failed', 'user',
                    body?.username, 'failed sign-in attempt')
            response.status = 401
            render([error: 'invalid credentials'] as JSON)
        }
    }

    /** Public: tells the login screen whether SSO is available. */
    def loginOptions() {
        boolean sso = grailsApplication.config.getProperty('svenager.sso.enabled', Boolean, false)
        render([
                sso     : sso,
                ssoUrl  : sso ? '/api/v1/auth/sso/oidc' : null,
                ssoLabel: sso ? grailsApplication.config.getProperty('svenager.sso.label', String,
                        'Single sign-on') : null,
        ] as JSON)
    }

    def me() {
        Authentication auth = SecurityContextHolder.context?.authentication
        render(userInfo(auth) as JSON)
    }

    private static Map userInfo(Authentication auth) {
        [
                username: auth.name,
                roles   : auth.authorities*.authority,
        ]
    }
}
