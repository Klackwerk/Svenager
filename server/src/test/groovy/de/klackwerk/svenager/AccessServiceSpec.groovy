package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import spock.lang.Specification

class AccessServiceSpec extends Specification implements ServiceUnitTest<AccessService>, DataTest {

    void setupSpec() {
        mockDomains(User, Device, DeviceGroup, GroupMembership, UserGroupScope)
    }

    void cleanup() {
        SecurityContextHolder.clearContext()
    }

    private void actAs(String username) {
        SecurityContextHolder.context.authentication =
                new UsernamePasswordAuthenticationToken(username, null, [])
    }

    private Device device(String hostname) {
        new Device(hostname: hostname, tokenHash: UUID.randomUUID().toString()).save(failOnError: true, flush: true)
    }

    void "scoped users see only their groups' devices; fleet-wide users everything"() {
        given: 'a kiosk in a scoped group and a foreign notebook'
        DeviceGroup kiosks = new DeviceGroup(name: 'kiosks').save(failOnError: true, flush: true)
        Device kiosk = device('kiosk-01')
        Device notebook = device('notebook-01')
        new GroupMembership(device: kiosk, deviceGroup: kiosks).save(failOnError: true, flush: true)
        User scoped = new User(username: 'scoped-op', passwordHash: 'x', role: UserRole.OPERATOR,
                allGroups: false).save(failOnError: true, flush: true)
        new UserGroupScope(user: scoped, deviceGroup: kiosks).save(failOnError: true, flush: true)
        new User(username: 'fleet-op', passwordHash: 'x', role: UserRole.OPERATOR).save(failOnError: true, flush: true)

        when:
        actAs('scoped-op')

        then:
        !service.fleetWide()
        service.canSeeDevice(kiosk)
        !service.canSeeDevice(notebook)
        service.canSeeGroup(kiosks)
        service.visibleDeviceIds() == [kiosk.id] as Set
        service.visibleGroupIds() == [kiosks.id] as Set

        when:
        actAs('fleet-op')

        then:
        service.fleetWide()
        service.canSeeDevice(notebook)
        service.visibleDeviceIds() == null
        service.visibleGroupIds() == null
    }

    void "a scoped user whose groups vanished sees nothing"() {
        given:
        Device stray = device('stray')
        new User(username: 'orphaned', passwordHash: 'x', role: UserRole.VIEWER, allGroups: false)
                .save(failOnError: true, flush: true)

        when:
        actAs('orphaned')

        then:
        !service.canSeeDevice(stray)
        service.visibleDeviceIds() == [] as Set
    }

    void "unknown principals (system, schedulers) stay fleet-wide"() {
        expect:
        service.fleetWide()
        service.visibleDeviceIds() == null
    }
}
