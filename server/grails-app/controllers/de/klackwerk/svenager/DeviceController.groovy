package de.klackwerk.svenager

import grails.converters.JSON
import groovy.json.JsonSlurper

class DeviceController {

    static allowedMethods = [index: 'GET', show: 'GET', update: 'PUT', delete: 'DELETE', setGroups: 'PUT',
                             variables: 'GET', replaceVariables: 'PUT', effectiveRoles: 'GET', apply: 'POST',
                             preview: 'POST', updateAgent: 'POST', reboot: 'POST']

    CheckinService checkinService
    GroupService groupService
    JobService jobService
    AuditService auditService
    AccessService accessService

    /** 404 for unknown AND out-of-scope devices — no existence leaks. */
    private Device visibleDevice(String id) {
        Device device = Device.findByUuid(id)
        (device != null && accessService.canSeeDevice(device)) ? device : null
    }

    private static final Map<String, String> SORTABLE = [
            hostname: 'hostname', ip: 'ip', lastContact: 'lastContactAt',
            lastJob: 'lastJobAt', agent: 'agentVersion', enrolled: 'dateCreated',
    ].asImmutable()

    /**
     * Paginated device listing with server-side filters: q (hostname, id or
     * agent version), status (online/offline/disabled, comma-separated) and
     * groupId (ids and/or 'none' for ungrouped, comma-separated).
     */
    def index() {
        int max = Math.min(params.int('max') ?: 50, 500)
        int offset = Math.max(params.int('offset') ?: 0, 0)
        String sortProp = SORTABLE[params.sort as String] ?: 'hostname'
        String sortOrder = params.order == 'desc' ? 'desc' : 'asc'
        String q = params.q?.toString()?.trim()
        List<String> statuses = (params.status ?: '').tokenize(',')
        List<String> groupFilters = (params.groupId ?: '').tokenize(',')

        Set<Long> groupIds = null
        if (groupFilters) {
            groupIds = [] as Set<Long>
            List<DeviceGroup> filterGroups = groupFilters.findAll { it != 'none' }
                    .collect { DeviceGroup.findByUuid(it) }.findAll()
            if (filterGroups) {
                groupIds.addAll(GroupMembership.findAllByDeviceGroupInList(filterGroups)*.device*.id)
            }
            if ('none' in groupFilters) {
                Set<Long> grouped = GroupMembership.list()*.device*.id as Set<Long>
                groupIds.addAll(Device.list()*.id.findAll { !(it in grouped) })
            }
        }

        // Online is derived per device (poll interval can differ by group),
        // so the status filter resolves to an id set instead of a criterion.
        Set<Long> statusIds = null
        if (statuses) {
            statusIds = [] as Set<Long>
            Device.list().each { Device d ->
                String state = d.status == DeviceStatus.DISABLED ? 'disabled'
                        : (checkinService.isOnline(d) ? 'online' : 'offline')
                if (state in statuses) {
                    statusIds << d.id
                }
            }
        }
        Set<Long> restrict = (groupIds != null && statusIds != null) ? groupIds.intersect(statusIds)
                : (groupIds != null ? groupIds : statusIds)
        Set<Long> scopeIds = accessService.visibleDeviceIds()
        if (scopeIds != null) {
            restrict = restrict == null ? scopeIds : restrict.intersect(scopeIds)
        }

        def devices = Device.createCriteria().list(max: max, offset: offset) {
            if (q) {
                or {
                    ilike('hostname', "%${q}%")
                    ilike('uuid', "%${q}%")
                    ilike('agentVersion', "%${q}%")
                }
            }
            if (restrict != null) {
                if (restrict) {
                    'in'('id', restrict as List<Long>)
                } else {
                    eq('id', -1L)
                }
            }
            order(sortProp, sortOrder)
        }
        List<Device> universe = scopeIds == null ? Device.list()
                : (scopeIds ? Device.getAll(scopeIds as List<Long>) : [])
        int online = universe.count { checkinService.isOnline(it) } as int
        render([items : devices.collect { summarize(it) }, total: devices.totalCount,
                offset: offset, max: max, online: online, all: universe.size()] as JSON)
    }

    def show(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        Map result = summarize(device)
        result.facts = device.factsJson ? new JsonSlurper().parseText(device.factsJson) : [:]
        result.variables = groupService.listVariables(null, device)
        render(result as JSON)
    }

    /**
     * Partial update: hostname (reaches the device via the base role) and/or
     * status (DISABLED devices are rejected at check-in until re-enabled).
     */
    def update(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        def body = request.JSON
        String hostname = body?.hostname?.toString()?.trim()
        if (body?.containsKey('hostname') && !(hostname ==~ /[A-Za-z0-9][A-Za-z0-9-]{0,62}/)) {
            respondError(422, 'hostname must be 1-63 letters, digits or dashes')
            return
        }
        DeviceStatus newStatus = null
        if (body?.containsKey('status')) {
            newStatus = DeviceStatus.values().find { it.name() == body.status?.toString() }
            if (newStatus == null) {
                respondError(422, 'status must be ACTIVE or DISABLED')
                return
            }
        }
        Device.withTransaction {
            if (hostname) {
                device.hostname = hostname
            }
            if (newStatus != null) {
                device.status = newStatus
            }
            device.save(failOnError: true)
        }
        List<String> changes = []
        if (hostname) {
            changes << "renamed to '${hostname}'".toString()
        }
        if (newStatus != null) {
            changes << (newStatus == DeviceStatus.DISABLED ? 'disabled' : 'enabled')
        }
        if (changes) {
            auditService.record('device-updated', 'device', device.uuid,
                    "device '${device.hostname}': ${changes.join(', ')}")
        }
        render(summarize(device) as JSON)
    }

    def delete(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        auditService.record('device-deleted', 'device', device.uuid, "removed device '${device.hostname}'")
        Device.withTransaction {
            groupService.deleteDevice(device)
        }
        response.status = 204
        render('')
    }

    def setGroups(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        List<Long> groupIds = (request.JSON?.groupIds ?: [])
                .collect { DeviceGroup.findByUuid(it as String)?.id }.findAll()
        Device.withTransaction { groupService.setDeviceGroups(device, groupIds) }
        List<DeviceGroup> groups = groupService.groupsOf(device)
        auditService.record('device-groups-set', 'device', device.uuid,
                "groups of '${device.hostname}' set to [${groups*.name.join(', ')}]")
        render(groups.collect { [id: it.uuid, name: it.name] } as JSON)
    }

    def apply(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        Date runAfter = parseRunAfter(request.JSON?.runAfter?.toString())
        if (runAfter == INVALID_RUN_AFTER) {
            respondError(422, 'runAfter must be an ISO-8601 instant')
            return
        }
        Job job = null
        Device.withTransaction {
            job = jobService.enqueueApply(device,
                    org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name,
                    null, runAfter)
        }
        response.status = 201
        render(JobController.summarize(job) as JSON)
    }

    static final Date INVALID_RUN_AFTER = new Date(0L)

    /** null for absent, INVALID_RUN_AFTER for unparseable input. */
    static Date parseRunAfter(String raw) {
        if (!raw) {
            return null
        }
        try {
            return Date.from(java.time.Instant.parse(raw))
        } catch (ignored) {
            return INVALID_RUN_AFTER
        }
    }

    /** Queues an agent self-update from the server's distribution files. */
    def updateAgent(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        String version = request.JSON?.version?.toString()?.trim()
        Job job = null
        Device.withTransaction {
            job = jobService.enqueueAgentUpdate(device, version,
                    org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name)
        }
        auditService.record('agent-update-queued', 'device', device.uuid,
                "queued agent update on '${device.hostname}'${version ? " to ${version}" : ''}")
        response.status = 201
        render(JobController.summarize(job) as JSON)
    }

    /** Queues a reboot of the device. */
    def reboot(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        Job job = null
        Device.withTransaction {
            job = jobService.enqueueReboot(device,
                    org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name)
        }
        auditService.record('device-reboot-queued', 'device', device.uuid,
                "queued reboot on '${device.hostname}'")
        response.status = 201
        render(JobController.summarize(job) as JSON)
    }

    /** Queues a check-mode run: same spec as apply, but changes nothing. */
    def preview(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        Job job = null
        Device.withTransaction {
            job = jobService.enqueuePreview(device,
                    org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name)
        }
        response.status = 201
        render(JobController.summarize(job) as JSON)
    }

    /** Roles that would run on this device — feeds the variable forms. */
    def effectiveRoles(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        List roles = groupService.effectiveRoles(groupService.groupsOf(device))
        render(roles.collect { RepositoryController.roleDetails(it) } as JSON)
    }

    def variables(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        render(groupService.listVariables(null, device) as JSON)
    }

    def replaceVariables(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        List entries = (request.JSON instanceof List) ? request.JSON as List : []
        Device.withTransaction { groupService.replaceVariables(null, device, entries as List<Map>) }
        auditService.record('variables-replaced', 'device', device.uuid,
                "device '${device.hostname}': ${AuditService.variablesSummary(entries as List<Map>)}")
        render(groupService.listVariables(null, device) as JSON)
    }

    private Map summarize(Device device) {
        [
                id           : device.uuid,
                groups       : groupService.groupsOf(device).collect { [id: it.uuid, name: it.name] },
                hostname     : device.hostname,
                status       : device.status.name(),
                online       : checkinService.isOnline(device),
                agentVersion : device.agentVersion,
                lastContactAt: device.lastContactAt?.toInstant()?.toString(),
                ip           : device.ip,
                lastIp       : device.lastIp,
                lastJobAt    : device.lastJobAt?.toInstant()?.toString(),
                enrolledAt   : device.dateCreated?.toInstant()?.toString(),
        ]
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
