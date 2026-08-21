package de.klackwerk.svenager

import de.klackwerk.svenager.security.Tokens
import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class EnrollmentServiceSpec extends Specification implements ServiceUnitTest<EnrollmentService>, DataTest {

    void setupSpec() {
        mockDomains(EnrollmentToken, Device, DeviceGroup, GroupMembership)
    }

    void setup() {
        service.groupService = new GroupService()
    }

    void "a group-targeted token puts the enrolled device into its groups"() {
        given:
        DeviceGroup kiosks = new DeviceGroup(name: 'kiosks').save(failOnError: true)
        DeviceGroup pilots = new DeviceGroup(name: 'pilots').save(failOnError: true)
        Map created = service.createToken('kiosk batch', 2, null, 'admin', [kiosks, pilots])

        when:
        Map result = service.enroll(created.token as String, 'kiosk-01', null)

        then: 'memberships exist, so the first check-in queues the apply'
        GroupMembership.findAllByDevice(result.device as Device)*.deviceGroup*.name.sort() ==
                ['kiosks', 'pilots']
    }

    void "createToken stores only the hash and returns the raw token once"() {
        when:
        Map result = service.createToken('kiosk batch', 5, null, 'admin')

        then:
        result.token.startsWith('svet_')
        EnrollmentToken.count() == 1
        with(EnrollmentToken.first()) {
            label == 'kiosk batch'
            maxUses == 5
            tokenHash == Tokens.hash(result.token as String)
            tokenHash != result.token
        }
    }

    void "enroll creates a device and issues a hashed device token"() {
        given:
        Map created = service.createToken('terminal', 1, null, 'admin')

        when:
        Map result = service.enroll(created.token as String, 'kiosk-01', [arch: 'arm64'])

        then:
        Device.count() == 1
        result.deviceToken.startsWith('svdt_')
        with(result.device as Device) {
            hostname == 'kiosk-01'
            tokenHash == Tokens.hash(result.deviceToken as String)
            factsJson.contains('arm64')
            lastContactAt != null
        }
        EnrollmentToken.first().usedCount == 1
    }

    void "enroll rejects unknown, exhausted, revoked and expired tokens"() {
        given:
        Map singleUse = service.createToken('single', 1, null, 'admin')
        service.enroll(singleUse.token as String, 'first', null)

        Map revoked = service.createToken('revoked', 10, null, 'admin')
        service.revoke((revoked.entity as EnrollmentToken).uuid)

        Map expired = service.createToken('expired', 10, new Date(System.currentTimeMillis() - 1000), 'admin')

        when:
        service.enroll(raw(singleUse), 'again', null)
        then:
        thrown(EnrollmentException)

        when:
        service.enroll(raw(revoked), 'host', null)
        then:
        thrown(EnrollmentException)

        when:
        service.enroll(raw(expired), 'host', null)
        then:
        thrown(EnrollmentException)

        when:
        service.enroll('svet_definitely-not-a-token', 'host', null)
        then:
        thrown(EnrollmentException)

        when:
        service.enroll(null, 'host', null)
        then:
        thrown(EnrollmentException)
    }

    private static String raw(Map created) {
        created.token as String
    }
}
