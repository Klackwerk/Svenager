package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import org.springframework.security.crypto.password.PasswordEncoder
import spock.lang.Specification

class UserServiceSpec extends Specification implements ServiceUnitTest<UserService>, DataTest {

    void setupSpec() {
        mockDomains(User)
    }

    void setup() {
        service.passwordEncoder = Stub(PasswordEncoder) {
            encode(_ as CharSequence) >> { CharSequence raw -> "hash:${raw}".toString() }
        }
    }

    private User admin(String name = 'admin') {
        new User(username: name, passwordHash: 'x', role: UserRole.ADMIN).save(failOnError: true)
    }

    void "create hashes the password and defaults to viewer"() {
        when:
        User user = service.create('erika', 'long-enough', null)

        then:
        user.role == UserRole.VIEWER
        user.passwordHash == 'hash:long-enough'
        user.enabled
    }

    void "create rejects duplicates and weak passwords"() {
        given:
        admin()

        when:
        service.create('admin', 'long-enough', UserRole.VIEWER)

        then:
        thrown(IllegalArgumentException)

        when:
        service.create('erika', 'short', UserRole.VIEWER)

        then:
        thrown(IllegalArgumentException)
    }

    void "update changes role, enabled flag and password"() {
        given:
        admin()
        User user = service.create('erika', 'long-enough', UserRole.VIEWER)

        when:
        service.update(user.uuid, UserRole.OPERATOR, false, 'brand-new-pass', 'admin')

        then:
        user.role == UserRole.OPERATOR
        !user.enabled
        user.passwordHash == 'hash:brand-new-pass'
    }

    void "you cannot demote yourself"() {
        given:
        User me = admin()

        when:
        service.update(me.uuid, UserRole.VIEWER, null, null, 'admin')

        then:
        thrown(IllegalArgumentException)
    }

    void "you cannot disable yourself"() {
        given:
        User me = admin()

        when:
        service.update(me.uuid, null, false, null, 'admin')

        then:
        thrown(IllegalArgumentException)
    }

    void "changing your own password is allowed"() {
        given:
        User me = admin()

        expect:
        service.update(me.uuid, null, null, 'brand-new-pass', 'admin').passwordHash == 'hash:brand-new-pass'
    }

    void "the last enabled admin cannot be demoted"() {
        given:
        User only = admin()

        when:
        service.update(only.uuid, UserRole.OPERATOR, null, null, 'someone-else')

        then:
        thrown(IllegalArgumentException)
    }

    void "an admin can be demoted while another enabled admin exists"() {
        given:
        User first = admin()
        admin('admin2')

        expect:
        service.update(first.uuid, UserRole.OPERATOR, null, null, 'someone-else').role == UserRole.OPERATOR
    }

    private User ssoUser() {
        admin()
        new User(username: 'sso-op', passwordHash: 'x', role: UserRole.OPERATOR, source: 'OIDC')
                .save(failOnError: true, flush: true)
    }

    void "SSO-managed users cannot get a local role change"() {
        given:
        User sso = ssoUser()

        when:
        service.update(sso.uuid, UserRole.ADMIN, null, null, 'admin')

        then:
        thrown(IllegalArgumentException)
    }

    void "SSO-managed users cannot get a local password"() {
        given:
        User sso = ssoUser()

        when:
        service.update(sso.uuid, null, null, 'brand-new-pass', 'admin')

        then:
        thrown(IllegalArgumentException)
    }

    void "SSO-managed users can still be disabled locally"() {
        expect:
        !service.update(ssoUser().uuid, null, false, null, 'admin').enabled
    }
}
