package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class SsoLoginServiceSpec extends Specification implements ServiceUnitTest<SsoLoginService>, DataTest {

    void setupSpec() {
        mockDomains(User, DeviceGroup, SsoGroupMapping, UserGroupScope)
    }

    void setup() {
        config.merge(['svenager.sso.adminGroup': 'infra-admins'] as Map<String, Object>)
    }

    void "members of the configured admin group become admins automatically"() {
        when:
        User user = service.login('alice', ['staff', 'infra-admins'])

        then:
        user.role == UserRole.ADMIN
        user.source == 'OIDC'
        user.allGroups
        UserGroupScope.count() == 0
    }

    void "dynamic mappings grant roles and device-group scope, synced per login"() {
        given:
        DeviceGroup kiosks = new DeviceGroup(name: 'kiosks').save(failOnError: true)
        DeviceGroup screens = new DeviceGroup(name: 'screens').save(failOnError: true)
        new SsoGroupMapping(idpGroup: 'kiosk-ops', role: UserRole.OPERATOR, deviceGroup: kiosks)
                .save(failOnError: true)
        new SsoGroupMapping(idpGroup: 'screen-ops', deviceGroup: screens).save(failOnError: true)

        when:
        User user = service.login('bob', ['kiosk-ops', 'screen-ops'])

        then: 'role from the mapping, scoped to both mapped groups'
        user.role == UserRole.OPERATOR
        !user.allGroups
        UserGroupScope.findAllByUser(user)*.deviceGroup*.name.sort() == ['kiosks', 'screens']

        when: 'the IdP no longer reports one group'
        user = service.login('bob', ['kiosk-ops'])

        then: 'the scope shrinks with it'
        UserGroupScope.findAllByUser(user)*.deviceGroup*.name == ['kiosks']
    }

    void "the highest mapped role wins and admins are never scoped"() {
        given:
        DeviceGroup kiosks = new DeviceGroup(name: 'kiosks').save(failOnError: true)
        new SsoGroupMapping(idpGroup: 'kiosk-ops', role: UserRole.VIEWER, deviceGroup: kiosks)
                .save(failOnError: true)

        when:
        User user = service.login('carol', ['kiosk-ops', 'infra-admins'])

        then:
        user.role == UserRole.ADMIN
        user.allGroups
        UserGroupScope.count() == 0
    }

    void "sign-ins without any mapped role are rejected"() {
        when:
        service.login('mallory', ['random-team'])

        then:
        thrown(SsoLoginException)
        User.count() == 0
    }

    void "a defaultRole admits unmapped users when configured"() {
        given:
        config.merge(['svenager.sso.defaultRole': 'viewer'] as Map<String, Object>)

        expect:
        service.login('dave', ['random-team']).role == UserRole.VIEWER
    }

    void "local accounts are never taken over and disabled accounts stay out"() {
        given:
        new User(username: 'admin', passwordHash: 'x', role: UserRole.ADMIN).save(failOnError: true)
        User blocked = service.login('eve', ['infra-admins'])
        blocked.enabled = false
        blocked.save(failOnError: true, flush: true)

        when: 'the IdP presents a name colliding with a local account'
        service.login('admin', ['infra-admins'])

        then:
        thrown(SsoLoginException)

        when: 'a disabled SSO user signs in'
        service.login('eve', ['infra-admins'])

        then:
        thrown(SsoLoginException)
    }

    void "the static roleMapping config also grants roles"() {
        given:
        config.merge(['svenager.sso.roleMapping': [operators: 'OPERATOR']] as Map<String, Object>)

        expect:
        service.login('frank', ['operators']).role == UserRole.OPERATOR
    }
}
