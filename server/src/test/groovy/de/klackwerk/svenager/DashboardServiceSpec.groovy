package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class DashboardServiceSpec extends Specification implements ServiceUnitTest<DashboardService>, DataTest {

    void setupSpec() {
        mockDomains(Device, DeviceGroup, GroupMembership, AnsibleRepository, DiscoveredRole,
                GroupRoleAssignment, ConfigVariable, Job, JobLogChunk)
    }

    GroupService groupService = new GroupService()

    void setup() {
        service.groupService = groupService
        service.checkinService = Stub(CheckinService) {
            isOnline(_) >> { Device d -> d.hostname.startsWith('on') }
        }
    }

    private Device device(String hostname) {
        new Device(hostname: hostname, tokenHash: UUID.randomUUID().toString(),
                lastContactAt: new Date(), lastJobAt: hostname.startsWith('on') ? new Date() : null)
                .save(failOnError: true)
    }

    private Job job(Device d, JobStatus status, Date created = new Date()) {
        Job j = new Job(device: d, status: status).save(failOnError: true, flush: true)
        j.dateCreated = created
        j.save(failOnError: true, flush: true)
        j
    }

    void "overview aggregates devices, groups and job statistics"() {
        given:
        Device on1 = device('on-kiosk-1')
        Device on2 = device('on-kiosk-2')
        Device off = device('off-kiosk')
        Device loner = device('on-ungrouped')

        DeviceGroup group = new DeviceGroup(name: 'terminals').save(failOnError: true)
        [on1, on2, off].each { groupService.addDevice(group, it) }

        job(on1, JobStatus.SUCCEEDED)
        job(on1, JobStatus.SUCCEEDED)
        job(on2, JobStatus.FAILED)
        job(off, JobStatus.TIMED_OUT)
        job(on2, JobStatus.RUNNING)
        job(loner, JobStatus.SUCCEEDED)
        // outside the 7-day window: ignored
        job(on1, JobStatus.FAILED, new Date(System.currentTimeMillis() - 30L * 24 * 3600_000))

        when:
        Map overview = service.overview()

        then:
        overview.devices == [total: 4, online: 3, offline: 1, ungrouped: 1]
        overview.jobs == [succeeded: 3, failed: 2, active: 1]

        and:
        overview.groups.size() == 1
        with(overview.groups[0]) {
            name == 'terminals'
            deviceCount == 3
            onlineCount == 2
            lastContactAt != null
            lastJobAt != null
            jobs == [succeeded: 2, failed: 2, active: 1]
        }
    }

    void "an empty installation produces zeroed statistics"() {
        expect:
        service.overview() == [
                devices   : [total: 0, online: 0, offline: 0, ungrouped: 0],
                jobs      : [succeeded: 0, failed: 0, active: 0],
                repos     : [total: 0, errors: 0, neverSynced: 0],
                groups    : [],
                windowDays: DashboardService.SUCCESS_WINDOW_DAYS,
        ]
    }
}
