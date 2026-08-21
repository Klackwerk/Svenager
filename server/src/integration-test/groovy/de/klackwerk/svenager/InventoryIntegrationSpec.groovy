package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/** Ansible dynamic-inventory export and global search. */
@Integration
class InventoryIntegrationSpec extends Specification {

    void "the inventory export lists devices under sanitized group names"() {
        given:
        ApiClient admin = new ApiClient(serverPort)
        admin.login('admin', 'admin')
        def token = admin.request('POST', '/api/v1/enrollment-tokens',
                [label: 'inventory spec', maxUses: 1, expiresInHours: 1], admin.csrfHeader())
        def enrolled = admin.request('POST', '/api/v1/enroll',
                [enrollmentToken: token.body.token, hostname: 'inv-kiosk-01'])
        def group = admin.request('POST', '/api/v1/groups',
                [name: 'Inventory Späc Group'], admin.csrfHeader())
        admin.request('POST', "/api/v1/groups/${group.body.id}/devices",
                [deviceId: enrolled.body.deviceId], admin.csrfHeader())

        when:
        def inventory = admin.request('GET', '/api/v1/inventory')

        then:
        inventory.status == 200
        inventory.body._meta.hostvars['inv-kiosk-01'].svenager_device_id == enrolled.body.deviceId
        inventory.body['Inventory_Sp_c_Group'].hosts == ['inv-kiosk-01']
        inventory.body.all.children.contains('Inventory_Sp_c_Group')

        and: 'global search finds the device and the group'
        with(admin.request('GET', '/api/v1/search?q=inv-kiosk').body) {
            devices*.hostname == ['inv-kiosk-01']
        }

        cleanup:
        admin.request('DELETE', "/api/v1/devices/${enrolled?.body?.deviceId}", null, admin.csrfHeader())
        admin.request('DELETE', "/api/v1/groups/${group?.body?.id}", null, admin.csrfHeader())
    }
}
