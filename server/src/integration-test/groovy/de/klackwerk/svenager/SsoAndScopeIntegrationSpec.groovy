package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/** SSO plumbing (options, mappings API) and group-scope enforcement. */
@Integration
class SsoAndScopeIntegrationSpec extends Specification {

    UserService userService

    void "login options are public and SSO is off by default in tests"() {
        given:
        ApiClient client = new ApiClient(serverPort)

        when:
        def options = client.request('GET', '/api/v1/auth/login-options')

        then:
        options.status == 200
        options.body.sso == false
    }

    void "SSO mappings are admin-only and CRUD works"() {
        given:
        ApiClient admin = new ApiClient(serverPort)
        admin.login('admin', 'admin')
        def group = admin.request('POST', '/api/v1/groups',
                [name: "sso-spec-group-${System.currentTimeMillis()}"], admin.csrfHeader())

        when: 'a mapping to a role and a device group is created'
        def created = admin.request('POST', '/api/v1/sso-mappings',
                [idpGroup: 'kiosk-ops', role: 'OPERATOR', deviceGroupId: group.body.id],
                admin.csrfHeader())

        then:
        created.status == 201
        created.body.role == 'OPERATOR'
        created.body.deviceGroupName == group.body.name
        admin.request('GET', '/api/v1/sso-mappings').body.find { it.id == created.body.id }

        when: 'a mapping without any effect is rejected'
        def rejected = admin.request('POST', '/api/v1/sso-mappings',
                [idpGroup: 'nothing'], admin.csrfHeader())

        then:
        rejected.status == 422

        when: 'a viewer tries the mappings API'
        String viewerName = "sso-viewer-${System.currentTimeMillis()}"
        admin.request('POST', '/api/v1/users',
                [username: viewerName, password: 'view-only-1', role: 'VIEWER'], admin.csrfHeader())
        ApiClient viewer = new ApiClient(serverPort)
        viewer.login(viewerName, 'view-only-1')

        then:
        viewer.request('GET', '/api/v1/sso-mappings').status == 403

        cleanup:
        admin.request('DELETE', "/api/v1/sso-mappings/${created?.body?.id}", null, admin.csrfHeader())
        admin.request('DELETE', "/api/v1/groups/${group?.body?.id}", null, admin.csrfHeader())
    }

    void "a scoped user sees only devices of their groups"() {
        given: 'two devices, one grouped, and an operator scoped to that group'
        ApiClient admin = new ApiClient(serverPort)
        admin.login('admin', 'admin')
        def token = admin.request('POST', '/api/v1/enrollment-tokens',
                [label: 'scope spec', maxUses: 2, expiresInHours: 1], admin.csrfHeader())
        def mine = admin.request('POST', '/api/v1/enroll',
                [enrollmentToken: token.body.token, hostname: 'scope-mine'])
        def foreign = admin.request('POST', '/api/v1/enroll',
                [enrollmentToken: token.body.token, hostname: 'scope-foreign'])
        def group = admin.request('POST', '/api/v1/groups',
                [name: "scope-spec-group-${System.currentTimeMillis()}"], admin.csrfHeader())
        admin.request('POST', "/api/v1/groups/${group.body.id}/devices",
                [deviceId: mine.body.deviceId], admin.csrfHeader())

        String scopedName = "scoped-op-${System.currentTimeMillis()}"
        userService.create(scopedName, 'scoped-pass-1', UserRole.OPERATOR)
        User.withTransaction {
            User user = User.findByUsername(scopedName)
            user.allGroups = false
            user.save(failOnError: true)
            new UserGroupScope(user: user,
                    deviceGroup: DeviceGroup.findByUuid(group.body.id as String)).save(failOnError: true)
        }
        ApiClient scoped = new ApiClient(serverPort)
        scoped.login(scopedName, 'scoped-pass-1')

        expect: 'the listing contains only the scoped device'
        with(scoped.request('GET', '/api/v1/devices').body) {
            items*.hostname == ['scope-mine']
            all == 1
        }

        and: 'the foreign device does not exist for the scoped user'
        scoped.request('GET', "/api/v1/devices/${foreign.body.deviceId}").status == 404
        admin.request('GET', "/api/v1/devices/${foreign.body.deviceId}").status == 200

        and: 'the group listing is filtered too'
        scoped.request('GET', '/api/v1/groups').body*.id == [group.body.id]

        and: 'job history cannot leak foreign devices'
        scoped.request('GET', "/api/v1/jobs?deviceId=${foreign.body.deviceId}").body.total == 0

        cleanup:
        [mine, foreign].each {
            admin.request('DELETE', "/api/v1/devices/${it?.body?.deviceId}", null, admin.csrfHeader())
        }
        admin.request('DELETE', "/api/v1/groups/${group?.body?.id}", null, admin.csrfHeader())
    }
}
