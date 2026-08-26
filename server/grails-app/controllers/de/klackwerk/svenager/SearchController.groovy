package de.klackwerk.svenager

import grails.converters.JSON

/**
 * Global search: devices by hostname/id, groups by name, Ansible roles by
 * name, jobs by id prefix. Device and group results respect the caller's
 * group scope; roles are visible to everyone who can read repositories.
 */
class SearchController {

    static allowedMethods = [index: 'GET']

    CheckinService checkinService
    AccessService accessService

    def index() {
        String q = params.q?.toString()?.trim()
        if (!q || q.length() < 2) {
            render([devices: [], groups: [], roles: [], jobs: []] as JSON)
            return
        }
        Set<Long> deviceScope = accessService.visibleDeviceIds()
        Set<Long> groupScope = accessService.visibleGroupIds()

        List<Device> devices = Device.createCriteria().list(max: 5) {
            or {
                ilike('hostname', "%${q}%")
                ilike('uuid', "%${q}%")
            }
            if (deviceScope != null) {
                if (deviceScope) {
                    'in'('id', deviceScope as List<Long>)
                } else {
                    eq('id', -1L)
                }
            }
            order('hostname', 'asc')
        } as List<Device>

        List<DeviceGroup> groups = DeviceGroup.createCriteria().list(max: 5) {
            ilike('name', "%${q}%")
            if (groupScope != null) {
                if (groupScope) {
                    'in'('id', groupScope as List<Long>)
                } else {
                    eq('id', -1L)
                }
            }
            order('name', 'asc')
        } as List<DeviceGroup>

        List<DiscoveredRole> roles = DiscoveredRole.createCriteria().list(max: 5) {
            or {
                ilike('name', "%${q}%")
                ilike('displayName', "%${q}%")
            }
            order('name', 'asc')
        } as List<DiscoveredRole>

        List<Job> jobs = []
        if (q.length() >= 4) {
            Job job = (Job.createCriteria().list(max: 1) {
                ilike('uuid', "${q}%")
            } as List<Job>).find { true }
            if (job != null && (deviceScope == null || job.device.id in deviceScope)) {
                jobs = [job]
            }
        }

        render([
                devices: devices.collect {
                    [id: it.uuid, hostname: it.hostname, online: checkinService.isOnline(it),
                     status: it.status.name()]
                },
                groups : groups.collect { [id: it.uuid, name: it.name] },
                roles  : roles.collect {
                    [id: it.uuid, name: it.name, displayName: it.displayName ?: it.name,
                     repository: it.repository.name, missing: it.missing]
                },
                jobs   : jobs.collect {
                    [id: it.uuid, hostname: it.device.hostname, status: it.status.name(),
                     type: it.type.name()]
                },
        ] as JSON)
    }
}
