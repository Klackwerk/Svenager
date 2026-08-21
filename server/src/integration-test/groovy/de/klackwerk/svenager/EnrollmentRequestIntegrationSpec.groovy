package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import org.springframework.security.crypto.password.PasswordEncoder
import spock.lang.Shared
import spock.lang.Specification

@Integration
class EnrollmentRequestIntegrationSpec extends Specification {

    @Shared
    PasswordEncoder passwordEncoder

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    void "a token-less device polls, an admin approves, credentials arrive exactly once"() {
        given: 'a pre-imaged device announcing itself'
        String machineId = "machine-${UUID.randomUUID()}"
        Map body = [requestId: machineId, hostname: 'cloned-pi', facts: [arch: 'arm64']]

        when: 'it polls without any token'
        def first = client.request('POST', '/api/v1/enroll/request', body)
        def second = client.request('POST', '/api/v1/enroll/request', body)

        then: 'it stays pending and appears once for review'
        first.status == 202
        second.body.status == 'pending'

        when: 'an admin reviews and approves it'
        client.login('admin', 'admin')
        def list = client.request('GET', '/api/v1/enrollment-requests')
        Map entry = list.body.find { it.requestId == machineId } as Map
        def approved = client.request('POST', "/api/v1/enrollment-requests/${entry.id}/approve", [:], client.csrfHeader())

        then:
        entry.status == 'PENDING'
        entry.hostname == 'cloned-pi'
        approved.status == 200
        approved.body.status == 'APPROVED'
        approved.body.decidedBy == 'admin'

        when: 'the device polls again'
        def granted = client.request('POST', '/api/v1/enroll/request', body)

        then: 'it receives its credentials'
        granted.status == 201
        granted.body.deviceId
        granted.body.deviceToken.startsWith('svdt_')

        and: 'the token actually works'
        client.request('POST', '/api/v1/agent/checkin', [agentVersion: 'it'],
                [Authorization: "Bearer ${granted.body.deviceToken}".toString()]).status == 200

        and: 'credentials are never handed out twice'
        client.request('POST', '/api/v1/enroll/request', body).status == 403
    }

    void "denied devices are told to stop and non-admins cannot decide"() {
        given: 'a pending request and a non-admin user'
        String machineId = "machine-${UUID.randomUUID()}"
        client.request('POST', '/api/v1/enroll/request', [requestId: machineId, hostname: 'stranger'])
        User.withNewTransaction {
            if (!User.findByUsername('viewer-it')) {
                new User(username: 'viewer-it', passwordHash: passwordEncoder.encode('viewer-it'),
                        role: UserRole.VIEWER).save(failOnError: true)
            }
        }

        when: 'the viewer tries to approve'
        client.login('viewer-it', 'viewer-it')
        String id = null
        EnrollmentRequest.withNewTransaction {
            id = EnrollmentRequest.findByRequestId(machineId).uuid
        }
        def forbidden = client.request('POST', "/api/v1/enrollment-requests/${id}/approve", [:], client.csrfHeader())

        then:
        forbidden.status == 403

        when: 'an admin denies instead'
        ApiClient adminClient = new ApiClient(serverPort)
        adminClient.login('admin', 'admin')
        adminClient.request('POST', "/api/v1/enrollment-requests/${id}/deny", [:], adminClient.csrfHeader())
        def answer = client.request('POST', '/api/v1/enroll/request', [requestId: machineId])

        then:
        answer.status == 403
        answer.body.status == 'denied'
    }
}
