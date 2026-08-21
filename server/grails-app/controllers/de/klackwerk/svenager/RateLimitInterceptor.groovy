package de.klackwerk.svenager

import grails.converters.JSON
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Throttles the unauthenticated public surface (enrollment, agent install,
 * kiosk demo/status) per client address, sign-ins per address + username,
 * and agent traffic per device id. Limits live under svenager.rateLimit in
 * application.yml; 0 disables one.
 */
class RateLimitInterceptor {

    RateLimitService rateLimitService

    private static final Map<String, String> LIMIT_KEYS = [
            enroll      : 'enrollPerMinute',
            agentInstall: 'installPerMinute',
            kioskDemo   : 'kioskDemoPerMinute',
    ]

    RateLimitInterceptor() {
        match(controller: 'enroll')
        match(controller: 'agentInstall')
        match(controller: 'kioskDemo')
        match(controller: 'agent')
        match(controller: 'auth', action: 'login')
    }

    boolean before() {
        String key
        int perMinute
        switch (controllerName) {
            case 'agent':
                // Keyed by device id, not address — one flooding device
                // cannot exhaust another device's budget behind NAT.
                String deviceId = SecurityContextHolder.context?.authentication?.name
                key = "agent:${deviceId ?: clientAddress()}"
                perMinute = limit('agentPerMinute')
                break
            case 'auth':
                String username = request.JSON?.username?.toString() ?: 'unknown'
                key = "login:${clientAddress()}:${username}"
                perMinute = limit('loginPerMinute')
                break
            default:
                key = "${controllerName}:${clientAddress()}"
                perMinute = limit(LIMIT_KEYS[controllerName])
        }
        if (rateLimitService.allow(key, perMinute)) {
            return true
        }
        response.status = 429
        response.setHeader('Retry-After', '60')
        render([error: 'too many requests — try again in a minute'] as JSON)
        false
    }

    private int limit(String name) {
        grailsApplication.config.getProperty("svenager.rateLimit.${name}", Integer, 0)
    }

    /**
     * X-Forwarded-For is only honored when the deployment declares a
     * trusted proxy in front — otherwise it is caller-controlled and would
     * let a flooder pick its own bucket.
     */
    private String clientAddress() {
        boolean trustForwarded = grailsApplication.config.getProperty(
                'svenager.rateLimit.trustForwardedFor', Boolean, false)
        if (trustForwarded) {
            String forwarded = request.getHeader('X-Forwarded-For')
            if (forwarded) {
                return forwarded.split(',')[0].trim()
            }
        }
        request.remoteAddr
    }
}
