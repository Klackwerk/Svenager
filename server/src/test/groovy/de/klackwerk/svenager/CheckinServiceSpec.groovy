package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class CheckinServiceSpec extends Specification implements ServiceUnitTest<CheckinService>, DataTest {

    void setupSpec() {
        mockDomains(Device, DeviceGroup, GroupMembership)
    }

    void setup() {
        service.jobService = Stub(JobService) {
            deliverNext(_) >> null
        }
        service.groupService = new GroupService()
    }

    private Device device(Map overrides = [:]) {
        new Device([hostname: 'kiosk-01', tokenHash: UUID.randomUUID().toString()] + overrides)
                .save(failOnError: true, flush: true)
    }

    void "checkin updates last contact, facts and agent version and returns no job"() {
        given:
        Device d = device()
        d.lastContactAt = null

        when:
        Map result = service.checkin(d, '0.1.0', [hostname: 'kiosk-renamed', arch: 'arm64'])

        then: 'the UI-managed name is not overwritten by the reported one'
        d.lastContactAt != null
        d.agentVersion == '0.1.0'
        d.hostname == 'kiosk-01'
        d.factsJson.contains('arm64')

        when: 'an unnamed device reports its hostname'
        Device fresh = device()
        fresh.hostname = ''
        service.checkin(fresh, '0.1.0', [hostname: 'reported'])

        then: 'the reported name seeds it'
        fresh.hostname == 'reported'
        result.pollIntervalSeconds == CheckinService.DEFAULT_POLL_INTERVAL_SECONDS
        result.job == null
    }

    void "checkin keeps the reported and the observed address apart"() {
        given:
        Device d = device()

        when:
        service.checkin(d, '0.1.0', [ip: '192.168.1.20', ip_addresses: '192.168.1.20, fd00::1'], '203.0.113.7')

        then:
        d.ip == '192.168.1.20'
        d.lastIp == '203.0.113.7'

        when: 'an older agent reports no address'
        service.checkin(d, '0.0.9', [hostname: 'kiosk-01'], '203.0.113.8')

        then: 'the last reported one is kept'
        d.ip == '192.168.1.20'
        d.lastIp == '203.0.113.8'
    }

    void "online detection uses the poll interval threshold"() {
        expect:
        service.isOnline(device(lastContactAt: new Date()))
        !service.isOnline(device(lastContactAt: new Date(System.currentTimeMillis() - 10 * 60_000)))
        !service.isOnline(device(lastContactAt: null))
        !service.isOnline(device(lastContactAt: new Date(), status: DeviceStatus.DISABLED))
    }

    void "a group can override the poll interval and the smallest override wins"() {
        given:
        Device d = device()
        DeviceGroup slow = new DeviceGroup(name: 'slow', pollIntervalSeconds: 600).save(failOnError: true)
        DeviceGroup fast = new DeviceGroup(name: 'fast', pollIntervalSeconds: 30).save(failOnError: true)

        expect: 'the default without any group'
        service.effectivePollIntervalSeconds(d) == CheckinService.DEFAULT_POLL_INTERVAL_SECONDS

        when:
        service.groupService.addDevice(slow, d)
        service.groupService.addDevice(fast, d)

        then:
        service.effectivePollIntervalSeconds(d) == 30
        service.checkin(d, '0.1.0', null).pollIntervalSeconds == 30
    }

    void "online detection respects a longer per-group interval"() {
        given: 'a device that last checked in 10 minutes ago'
        Device d = device(lastContactAt: new Date(System.currentTimeMillis() - 10 * 60_000))

        expect: 'offline with the default 60s interval'
        !service.isOnline(d)

        when: 'its group polls every 30 minutes'
        DeviceGroup g = new DeviceGroup(name: 'slow-lane', pollIntervalSeconds: 1800).save(failOnError: true)
        service.groupService.addDevice(g, d)

        then:
        service.isOnline(d)
    }
}
