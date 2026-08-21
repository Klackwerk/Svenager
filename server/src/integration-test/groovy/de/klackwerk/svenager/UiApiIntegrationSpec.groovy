package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/** Session auth, CSRF and the UI-facing endpoints end to end. */
@Integration
class UiApiIntegrationSpec extends Specification {

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    void "actuator exposes health only and locks everything else down"() {
        expect:
        client.request('GET', '/actuator/health').status == 200
        client.request('GET', '/actuator/env').status in [401, 404]
        client.request('GET', '/actuator/beans').status in [401, 404]
    }

    void "UI endpoints require a session"() {
        expect:
        client.request('GET', '/api/v1/devices').status == 401
        client.request('GET', '/api/v1/enrollment-tokens').status == 401
        client.request('GET', '/api/v1/auth/me').status == 401
    }

    void "login rejects wrong credentials"() {
        expect:
        client.login('admin', 'definitely-wrong').status == 401
    }

    void "login without a CSRF token is rejected"() {
        given: 'a CSRF cookie exists but is not sent as header'
        client.request('GET', '/api/v1/auth/me')

        expect:
        client.request('POST', '/api/v1/auth/login', [username: 'admin', password: 'admin']).status == 403
    }

    void "an admin can log in and manage enrollment tokens and devices"() {
        when:
        def login = client.login('admin', 'admin')

        then:
        login.status == 200
        login.body.username == 'admin'
        login.body.roles.contains('ROLE_ADMIN')

        when: 'the session works for reads'
        def me = client.request('GET', '/api/v1/auth/me')

        then:
        me.status == 200

        when: 'a token is created (raw value returned exactly once)'
        def created = client.request('POST', '/api/v1/enrollment-tokens',
                [label: 'ui spec', maxUses: 1, expiresInHours: 1], client.csrfHeader())

        then:
        created.status == 201
        created.body.token.startsWith('svet_')
        created.body.createdBy == 'admin'

        and: 'the raw token is not in the listing'
        def listing = client.request('GET', '/api/v1/enrollment-tokens')
        listing.status == 200
        listing.body.every { it.token == null }

        when: 'a device enrolled with it shows up and can be deleted'
        def enroll = client.request('POST', '/api/v1/enroll',
                [enrollmentToken: created.body.token, hostname: 'ui-spec-device'])
        def devices = client.request('GET', '/api/v1/devices')

        then:
        enroll.status == 201
        devices.body.items.find { it.hostname == 'ui-spec-device' }

        when:
        String deviceId = devices.body.items.find { it.hostname == 'ui-spec-device' }.id
        def deleted = client.request('DELETE', "/api/v1/devices/${deviceId}", null, client.csrfHeader())

        then:
        deleted.status == 204
        !client.request('GET', '/api/v1/devices').body.items.find { it.hostname == 'ui-spec-device' }

        and: 'the orphaned device token is now rejected'
        client.request('POST', '/api/v1/agent/checkin', [:],
                [Authorization: "Bearer ${enroll.body.deviceToken}".toString()]).status == 401

        when: 'logout ends the session'
        def logout = client.request('POST', '/api/v1/auth/logout', null, client.csrfHeader())

        then:
        logout.status == 200
        client.request('GET', '/api/v1/devices').status == 401
    }
}
