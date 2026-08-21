package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/** The audit trail records actions and is readable by admins only. */
@Integration
class AuditIntegrationSpec extends Specification {

    void "logins are audited and only admins can read the trail"() {
        given: 'a failed and a successful admin login'
        ApiClient admin = new ApiClient(serverPort)
        admin.login('admin', 'definitely-wrong')
        admin.login('admin', 'admin')

        when: 'the admin reads the audit trail'
        def audit = admin.request('GET', '/api/v1/audit?max=100')

        then:
        audit.status == 200
        audit.body.items.find { it.action == 'login' && it.actor == 'admin' }
        audit.body.items.find { it.action == 'login-failed' && it.actor == 'admin' }

        when: 'a viewer account tries to read it'
        String viewerName = "audit-viewer-${System.currentTimeMillis()}"
        def created = admin.request('POST', '/api/v1/users',
                [username: viewerName, password: 'view-only-1', role: 'VIEWER'], admin.csrfHeader())
        ApiClient viewer = new ApiClient(serverPort)
        def viewerLogin = viewer.login(viewerName, 'view-only-1')

        then:
        created.status == 201
        viewerLogin.status == 200
        viewer.request('GET', '/api/v1/audit').status == 403

        and: 'the user creation itself is on the trail'
        admin.request('GET', "/api/v1/audit?q=${viewerName}").body.items
                .find { it.action == 'user-created' && it.actor == 'admin' }
    }
}
