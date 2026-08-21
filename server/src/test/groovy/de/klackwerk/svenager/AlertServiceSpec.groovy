package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class AlertServiceSpec extends Specification implements ServiceUnitTest<AlertService>, DataTest {

    List<String> sent = []

    void setupSpec() {
        mockDomains(Device, DeviceGroup, GroupMembership)
    }

    void setup() {
        service.groupService = new GroupService()
        service.notificationService = Stub(NotificationService) {
            deviceOffline(_, _) >> { Device d, int s -> sent << "offline:${d.hostname}".toString() }
            deviceRecovered(_) >> { Device d -> sent << "recovered:${d.hostname}".toString() }
        }
    }

    private Device device(Map overrides = [:]) {
        new Device([hostname: 'kiosk-01', tokenHash: UUID.randomUUID().toString()] + overrides)
                .save(failOnError: true, flush: true)
    }

    void "a silent device alerts exactly once and recovers exactly once"() {
        given: 'a device silent for one hour (default threshold 600s)'
        Device d = device(lastContactAt: new Date(System.currentTimeMillis() - 3600_000))

        when: 'the sweep runs three times'
        3.times { service.sweepOfflineAlerts() }

        then: 'only one offline alert was sent'
        sent == ['offline:kiosk-01']
        d.offlineAlertedAt != null

        when: 'the device checks in again and sweeps continue'
        d.lastContactAt = new Date()
        d.save(failOnError: true, flush: true)
        3.times { service.sweepOfflineAlerts() }

        then: 'exactly one recovery alert'
        sent == ['offline:kiosk-01', 'recovered:kiosk-01']
        d.offlineAlertedAt == null
    }

    void "devices that never checked in or are disabled do not alert"() {
        given:
        device(lastContactAt: null)
        device(lastContactAt: new Date(System.currentTimeMillis() - 3600_000), status: DeviceStatus.DISABLED)

        expect:
        service.sweepOfflineAlerts() == 0
        sent.isEmpty()
    }

    void "a group can lengthen the offline threshold; the most tolerant wins"() {
        given: 'a device silent for 30 minutes'
        Device d = device(lastContactAt: new Date(System.currentTimeMillis() - 30 * 60_000))
        DeviceGroup notebooks = new DeviceGroup(name: 'notebooks', offlineAlertSeconds: 86400)
                .save(failOnError: true)
        DeviceGroup kiosks = new DeviceGroup(name: 'kiosks', offlineAlertSeconds: 120)
                .save(failOnError: true)
        service.groupService.addDevice(notebooks, d)
        service.groupService.addDevice(kiosks, d)

        expect: 'no alert — the notebook threshold (1 day) applies'
        service.effectiveOfflineAfterSeconds(d) == 86400
        service.sweepOfflineAlerts() == 0

        when: 'the device is only in the kiosk group'
        service.groupService.removeDevice(notebooks, d)

        then: 'the 2-minute kiosk threshold triggers the alert'
        service.sweepOfflineAlerts() == 1
        sent == ['offline:kiosk-01']
    }
}
