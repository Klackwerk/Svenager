package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class GroupServiceSpec extends Specification implements ServiceUnitTest<GroupService>, DataTest {

    void setupSpec() {
        mockDomains(Device, DeviceGroup, GroupMembership, AnsibleRepository, DiscoveredRole,
                GroupRoleAssignment, ConfigVariable, Job, JobBatch, User, SsoGroupMapping, UserGroupScope,
                EnrollmentToken)
    }

    void setup() {
        service.cryptoService = Stub(CryptoService) {
            encrypt(_) >> { String s -> "enc(${s})".toString() }
            decrypt(_) >> { String s -> s[4..-2] }
        }
    }

    private Device device(String hostname = 'kiosk-01') {
        new Device(hostname: hostname, tokenHash: UUID.randomUUID().toString()).save(failOnError: true)
    }

    private DeviceGroup group(String name = 'terminals') {
        new DeviceGroup(name: name).save(failOnError: true)
    }

    private DiscoveredRole role(String name) {
        AnsibleRepository repo = AnsibleRepository.first() ?:
                new AnsibleRepository(name: 'main', gitUrl: 'https://example.org/x.git').save(failOnError: true)
        new DiscoveredRole(repository: repo, name: name).save(failOnError: true)
    }

    void "setDeviceGroups replaces membership idempotently"() {
        given:
        Device d = device()
        DeviceGroup a = group('a')
        DeviceGroup b = group('b')

        when:
        service.setDeviceGroups(d, [a.id, b.id])
        service.setDeviceGroups(d, [b.id])

        then:
        service.groupsOf(d)*.name == ['b']
        service.membersOf(a).empty
    }

    void "role assignments keep a stable order and can be reordered"() {
        given:
        DeviceGroup g = group()
        def first = service.assignRole(g, role('base'))
        def second = service.assignRole(g, role('kiosk'))
        def third = service.assignRole(g, role('vnc'))

        expect:
        service.assignmentsOf(g)*.role*.name == ['base', 'kiosk', 'vnc']

        when: 'assigning again does not duplicate'
        service.assignRole(g, DiscoveredRole.findByName('base'))

        then:
        service.assignmentsOf(g).size() == 3

        when:
        service.reorderRoles(g, [third.id, first.id, second.id])

        then:
        service.assignmentsOf(g)*.role*.name == ['vnc', 'base', 'kiosk']
    }

    void "effectiveRoles lists base roles of involved repos first, then assignments"() {
        given:
        DeviceGroup g = group()
        DiscoveredRole kiosk = role('kiosk')
        DiscoveredRole banner = role('banner')
        new DiscoveredRole(repository: kiosk.repository, name: 'svenager_base',
                baseRole: true, userAssignable: false).save(failOnError: true)
        DiscoveredRole disabled = role('disabled_role')
        service.assignRole(g, kiosk)
        service.assignRole(g, banner)
        service.assignRole(g, disabled).enabled = false

        expect: 'base first, assignment order kept, disabled excluded'
        service.effectiveRoles([g])*.name == ['svenager_base', 'kiosk', 'banner']

        and: 'a group without assignments has no effective roles'
        service.effectiveRoles([group('empty')]).empty
    }

    void "replaceVariables upserts, deletes and encrypts secrets"() {
        given:
        DeviceGroup g = group()

        when:
        service.replaceVariables(g, null, [
                [name: 'kiosk_url', value: 'https://example.org', secret: false],
                [name: 'wifi_psk', value: 'hunter2', secret: true],
        ])

        then:
        service.listVariables(g, null) == [
                [name: 'kiosk_url', secret: false, value: 'https://example.org'],
                [name: 'wifi_psk', secret: true, value: null],
        ]
        ConfigVariable.findByName('wifi_psk').valueJson.startsWith('enc(')

        when: 'an untouched secret keeps its stored value, removed vars vanish'
        service.replaceVariables(g, null, [
                [name: 'wifi_psk', value: null, secret: true],
        ])

        then:
        ConfigVariable.count() == 1
        ConfigVariable.findByName('wifi_psk').valueJson == 'enc("hunter2")'
    }

    void "deleting a group cleans memberships, assignments and variables"() {
        given:
        DeviceGroup g = group()
        service.addDevice(g, device())
        service.assignRole(g, role('base'))
        service.replaceVariables(g, null, [[name: 'x', value: 1, secret: false]])

        when:
        service.deleteGroup(g)

        then:
        DeviceGroup.count() == 0
        GroupMembership.count() == 0
        GroupRoleAssignment.count() == 0
        ConfigVariable.count() == 0
        Device.count() == 1
    }
}
