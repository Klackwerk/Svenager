package de.klackwerk.svenager

import grails.converters.JSON

/**
 * Read-only Ansible dynamic-inventory JSON so professionals can run
 * push-mode ad hoc from a workstation:
 *   ansible-inventory -i <(curl -b ... /api/v1/inventory) --list
 * Hosts are the device hostnames; group names are sanitized to valid
 * Ansible identifiers. Results respect the caller's group scope.
 */
class InventoryController {

    static allowedMethods = [index: 'GET']

    AccessService accessService

    def index() {
        Set<Long> deviceScope = accessService.visibleDeviceIds()
        Set<Long> groupScope = accessService.visibleGroupIds()
        List<Device> devices = Device.list(sort: 'hostname').findAll {
            deviceScope == null || it.id in deviceScope
        }
        List<DeviceGroup> groups = DeviceGroup.list(sort: 'name').findAll {
            groupScope == null || it.id in groupScope
        }

        Map hostvars = [:]
        devices.each { Device device ->
            Map vars = [svenager_device_id: device.uuid]
            if (device.lastIp) {
                vars.ansible_host = device.lastIp
            }
            hostvars[device.hostname] = vars
        }

        Map inventory = ['_meta': [hostvars: hostvars]]
        List<String> groupKeys = []
        groups.each { DeviceGroup group ->
            String key = sanitize(group.name)
            inventory[key] = [hosts: GroupMembership.findAllByDeviceGroup(group)*.device
                    .findAll { deviceScope == null || it.id in deviceScope }
                    *.hostname.sort()]
            groupKeys << key
        }
        inventory['ungrouped'] = [hosts: devices.findAll { !GroupMembership.countByDevice(it) }
                *.hostname.sort()]
        inventory['all'] = [children: ['ungrouped'] + groupKeys]
        render(inventory as JSON)
    }

    /** Ansible group names: word characters only, never digit-leading. */
    private static String sanitize(String name) {
        String cleaned = name.replaceAll(/[^A-Za-z0-9_]/, '_')
        cleaned ==~ /^[0-9].*/ ? 'g_' + cleaned : cleaned
    }
}
