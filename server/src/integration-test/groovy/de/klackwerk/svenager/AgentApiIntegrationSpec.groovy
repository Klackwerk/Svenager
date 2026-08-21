package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Shared
import spock.lang.Specification

/** Full HTTP round-trips through the agent security chain. */
@Integration
class AgentApiIntegrationSpec extends Specification {

    @Shared
    EnrollmentService enrollmentService

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    private String freshEnrollmentToken(int maxUses = 1) {
        String raw = null
        EnrollmentToken.withNewTransaction {
            raw = enrollmentService.createToken('integration', maxUses, null, 'spec').token
        }
        raw
    }

    void "a device can enroll and then check in with its device token"() {
        given:
        String enrollmentToken = freshEnrollmentToken()

        when: 'the device enrolls'
        def enroll = client.request('POST', '/api/v1/enroll',
                [enrollmentToken: enrollmentToken, hostname: 'it-kiosk', facts: [arch: 'arm64']])

        then:
        enroll.status == 201
        enroll.body.deviceId
        enroll.body.deviceToken.startsWith('svdt_')

        when: 'it checks in with the issued token'
        def checkin = client.request('POST', '/api/v1/agent/checkin',
                [agentVersion: '9.9.9', facts: [hostname: 'it-kiosk']],
                [Authorization: "Bearer ${enroll.body.deviceToken}".toString()])

        then:
        checkin.status == 200
        checkin.body.pollIntervalSeconds > 0
        checkin.body.job == null

        and: 'the device is persisted with the reported agent version'
        Device.withNewTransaction {
            Device device = Device.findByUuid(enroll.body.deviceId as String)
            assert device.agentVersion == '9.9.9'
            assert device.lastContactAt != null
            true
        }
    }

    void "enrollment is rejected for invalid and reused tokens"() {
        given:
        String token = freshEnrollmentToken(1)
        client.request('POST', '/api/v1/enroll', [enrollmentToken: token, hostname: 'first'])

        expect: 'reuse beyond maxUses fails'
        client.request('POST', '/api/v1/enroll', [enrollmentToken: token, hostname: 'second']).status == 403

        and: 'garbage fails'
        client.request('POST', '/api/v1/enroll', [enrollmentToken: 'svet_nope', hostname: 'x']).status == 403

        and: 'missing token fails'
        client.request('POST', '/api/v1/enroll', [hostname: 'x']).status == 403
    }

    void "agent endpoints reject missing or invalid bearer tokens"() {
        expect:
        client.request('POST', '/api/v1/agent/checkin', [:]).status == 401
        client.request('POST', '/api/v1/agent/checkin', [:], [Authorization: 'Bearer svdt_forged']).status == 401
    }

    void "device tokens grant no access to UI endpoints"() {
        given:
        String enrollmentToken = freshEnrollmentToken()
        def enroll = client.request('POST', '/api/v1/enroll', [enrollmentToken: enrollmentToken, hostname: 'sneaky'])

        expect: 'the device token must not read the device inventory'
        client.request('GET', '/api/v1/devices', null,
                [Authorization: "Bearer ${enroll.body.deviceToken}".toString()]).status == 401
    }
}
