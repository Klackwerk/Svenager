package de.klackwerk.svenager

import grails.testing.web.interceptor.InterceptorUnitTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import spock.lang.Specification

class RateLimitInterceptorSpec extends Specification implements InterceptorUnitTest<RateLimitInterceptor> {

    void setup() {
        interceptor.rateLimitService = new RateLimitService()
        config.merge([
                'svenager.rateLimit.loginPerMinute': 3,
                'svenager.rateLimit.agentPerMinute': 2,
        ] as Map<String, Object>)
    }

    void cleanup() {
        SecurityContextHolder.clearContext()
    }

    void "sign-in attempts are throttled per address and username"() {
        given:
        request.json = '{"username":"admin","password":"guess"}'
        withRequest(controller: 'auth', action: 'login')
        webRequest.controllerName = 'auth'

        expect: 'the first three attempts pass, the fourth hits 429'
        interceptor.before()
        interceptor.before()
        interceptor.before()
        !interceptor.before()
        response.status == 429
    }

    void "agent traffic is throttled per device id, not per address"() {
        given:
        withRequest(controller: 'agent')
        webRequest.controllerName = 'agent'
        SecurityContextHolder.context.authentication =
                new UsernamePasswordAuthenticationToken('device-a', null, [])

        expect: 'device-a uses up its own budget'
        interceptor.before()
        interceptor.before()
        !interceptor.before()
        response.status == 429

        when: 'a different device arrives from the same address'
        SecurityContextHolder.context.authentication =
                new UsernamePasswordAuthenticationToken('device-b', null, [])

        then: 'it has its own budget'
        interceptor.before()
    }
}
