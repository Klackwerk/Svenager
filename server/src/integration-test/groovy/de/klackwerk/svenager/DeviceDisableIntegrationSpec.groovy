package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/** Disable/enable end to end: a disabled device is rejected at check-in. */
@Integration
class DeviceDisableIntegrationSpec extends Specification {

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    void "a disabled device is rejected at check-in and restored on enable"() {
        given:
        client.login('admin', 'admin')
        def token = client.request('POST', '/api/v1/enrollment-tokens',
                [label: 'disable spec', maxUses: 1, expiresInHours: 1], client.csrfHeader())
        def enroll = client.request('POST', '/api/v1/enroll',
                [enrollmentToken: token.body.token, hostname: 'disable-spec'])
        String deviceId = enroll.body.deviceId
        Map bearer = [Authorization: "Bearer ${enroll.body.deviceToken}".toString()]

        expect: 'check-in works while active'
        client.request('POST', '/api/v1/agent/checkin', [:], bearer).status == 200

        when: 'an operator disables the device'
        def disabled = client.request('PUT', "/api/v1/devices/${deviceId}",
                [status: 'DISABLED'], client.csrfHeader())

        then: 'the check-in is rejected'
        disabled.body.status == 'DISABLED'
        client.request('POST', '/api/v1/agent/checkin', [:], bearer).status == 401

        when: 'the device is re-enabled'
        client.request('PUT', "/api/v1/devices/${deviceId}", [status: 'ACTIVE'], client.csrfHeader())

        then:
        client.request('POST', '/api/v1/agent/checkin', [:], bearer).status == 200

        cleanup:
        client.request('DELETE', "/api/v1/devices/${deviceId}", null, client.csrfHeader())
    }
}
