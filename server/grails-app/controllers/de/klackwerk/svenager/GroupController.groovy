package de.klackwerk.svenager

import grails.converters.JSON

class GroupController {

    static allowedMethods = [index: 'GET', save: 'POST', show: 'GET', update: 'PUT', delete: 'DELETE',
                             addDevice: 'POST', removeDevice: 'DELETE',
                             addRole: 'POST', removeRole: 'DELETE', reorderRoles: 'PUT', effectiveRoles: 'GET',
                             variables: 'GET', replaceVariables: 'PUT', apply: 'POST']

    GroupService groupService
    CheckinService checkinService
    JobService jobService
    AuditService auditService
    AccessService accessService

    /** 404 for unknown AND out-of-scope groups — no existence leaks. */
    private DeviceGroup visibleGroup(String id) {
        DeviceGroup group = DeviceGroup.findByUuid(id)
        (group != null && accessService.canSeeGroup(group)) ? group : null
    }

    def index() {
        Set<Long> visible = accessService.visibleGroupIds()
        List<DeviceGroup> groups = DeviceGroup.list(sort: 'name')
        if (visible != null) {
            groups = groups.findAll { it.id in visible }
        }
        render(groups.collect { summarize(it) } as JSON)
    }

    def save() {
        def body = request.JSON
        String name = body?.name?.toString()?.trim()
        if (!name) {
            respondError(422, 'name is required')
            return
        }
        if (DeviceGroup.findByName(name)) {
            respondError(409, 'a group with this name already exists')
            return
        }
        DeviceGroup group = null
        DeviceGroup.withTransaction {
            group = new DeviceGroup(name: name, description: body?.description?.toString() ?: null)
                    .save(failOnError: true, flush: true)
        }
        auditService.record('group-created', 'group', group.uuid, "created group '${group.name}'")
        response.status = 201
        render(summarize(group) as JSON)
    }

    def show(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        Map result = summarize(group)
        result.devices = groupService.membersOf(group).collect {
            [id: it.uuid, hostname: it.hostname, online: checkinService.isOnline(it)]
        }
        result.roles = groupService.assignmentsOf(group).collect { assignment(it) }
        result.variables = groupService.listVariables(group, null)
        render(result as JSON)
    }

    def update(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        def body = request.JSON
        Integer pollInterval = null
        boolean setPollInterval = body?.containsKey('pollIntervalSeconds')
        if (setPollInterval && body.pollIntervalSeconds != null) {
            String raw = body.pollIntervalSeconds.toString()
            pollInterval = raw.isInteger() ? raw.toInteger() : null
            if (pollInterval == null || pollInterval < 10 || pollInterval > 86400) {
                respondError(422, 'pollIntervalSeconds must be between 10 and 86400')
                return
            }
        }
        Integer offlineAlert = null
        boolean setOfflineAlert = body?.containsKey('offlineAlertSeconds')
        if (setOfflineAlert && body.offlineAlertSeconds != null) {
            String raw = body.offlineAlertSeconds.toString()
            offlineAlert = raw.isInteger() ? raw.toInteger() : null
            if (offlineAlert == null || offlineAlert < 60 || offlineAlert > 604800) {
                respondError(422, 'offlineAlertSeconds must be between 60 and 604800')
                return
            }
        }
        DeviceGroup.withTransaction {
            if (body?.name) {
                group.name = body.name.toString().trim()
            }
            if (body?.containsKey('description')) {
                group.description = body.description?.toString()
            }
            if (setPollInterval) {
                group.pollIntervalSeconds = pollInterval
            }
            if (setOfflineAlert) {
                group.offlineAlertSeconds = offlineAlert
            }
            group.save(failOnError: true, flush: true)
        }
        auditService.record('group-updated', 'group', group.uuid, "updated settings of group '${group.name}'")
        render(summarize(group) as JSON)
    }

    def delete(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        auditService.record('group-deleted', 'group', group.uuid, "deleted group '${group.name}'")
        DeviceGroup.withTransaction { groupService.deleteGroup(group) }
        response.status = 204
        render('')
    }

    def addDevice(String id) {
        DeviceGroup group = visibleGroup(id)
        Device device = Device.findByUuid(request.JSON?.deviceId as String)
        if (device != null && !accessService.canSeeDevice(device)) {
            device = null
        }
        if (!group || !device) {
            respondError(404, 'group or device not found')
            return
        }
        DeviceGroup.withTransaction { groupService.addDevice(group, device) }
        auditService.record('group-device-added', 'group', group.uuid,
                "added device '${device.hostname}' to group '${group.name}'")
        response.status = 204
        render('')
    }

    def removeDevice(String id, String deviceId) {
        DeviceGroup group = visibleGroup(id)
        Device device = Device.findByUuid(deviceId)
        if (!group || !device) {
            respondError(404, 'group or device not found')
            return
        }
        DeviceGroup.withTransaction { groupService.removeDevice(group, device) }
        auditService.record('group-device-removed', 'group', group.uuid,
                "removed device '${device.hostname}' from group '${group.name}'")
        response.status = 204
        render('')
    }

    def addRole(String id) {
        DeviceGroup group = visibleGroup(id)
        DiscoveredRole role = DiscoveredRole.findByUuid(request.JSON?.roleId as String)
        if (!group || !role) {
            respondError(404, 'group or role not found')
            return
        }
        GroupRoleAssignment created = null
        DeviceGroup.withTransaction { created = groupService.assignRole(group, role) }
        auditService.record('group-role-assigned', 'group', group.uuid,
                "assigned role '${role.name}' to group '${group.name}'")
        response.status = 201
        render(assignment(created) as JSON)
    }

    def removeRole(String id, String assignmentId) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        GroupRoleAssignment assignment = GroupRoleAssignment.findByUuid(assignmentId)
        String roleName = assignment?.role?.name
        DeviceGroup.withTransaction { groupService.unassignRole(group, assignment?.id) }
        auditService.record('group-role-removed', 'group', group.uuid,
                "removed role '${roleName ?: assignmentId}' from group '${group.name}'")
        response.status = 204
        render('')
    }

    def reorderRoles(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        List<Long> order = (request.JSON?.assignmentIds ?: [])
                .collect { GroupRoleAssignment.findByUuid(it as String)?.id }.findAll()
        DeviceGroup.withTransaction { groupService.reorderRoles(group, order) }
        List assignments = groupService.assignmentsOf(group)
        auditService.record('group-roles-reordered', 'group', group.uuid,
                "role order of '${group.name}' is now: ${assignments*.role*.name.join(' → ')}")
        render(assignments.collect { assignment(it) } as JSON)
    }

    def apply(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        boolean canary = request.JSON?.canary == true
        Date runAfter = DeviceController.parseRunAfter(request.JSON?.runAfter?.toString())
        if (runAfter == DeviceController.INVALID_RUN_AFTER) {
            respondError(422, 'runAfter must be an ISO-8601 instant')
            return
        }
        JobBatch batch = null
        DeviceGroup.withTransaction {
            batch = jobService.enqueueBatchForGroup(group,
                    org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name,
                    canary, runAfter)
        }
        response.status = 201
        render(JobBatchController.summarize(batch) as JSON)
    }

    def variables(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        render(groupService.listVariables(group, null) as JSON)
    }

    /** Roles that would run for this group — feeds the variable forms. */
    def effectiveRoles(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        render(groupService.effectiveRoles([group]).collect { RepositoryController.roleDetails(it) } as JSON)
    }

    def replaceVariables(String id) {
        DeviceGroup group = visibleGroup(id)
        if (!group) {
            respondError(404, 'group not found')
            return
        }
        List entries = (request.JSON instanceof List) ? request.JSON as List : []
        DeviceGroup.withTransaction { groupService.replaceVariables(group, null, entries as List<Map>) }
        auditService.record('variables-replaced', 'group', group.uuid,
                "group '${group.name}': ${AuditService.variablesSummary(entries as List<Map>)}")
        render(groupService.listVariables(group, null) as JSON)
    }

    private Map summarize(DeviceGroup group) {
        [
                id                 : group.uuid,
                name               : group.name,
                description        : group.description,
                pollIntervalSeconds: group.pollIntervalSeconds,
                offlineAlertSeconds: group.offlineAlertSeconds,
                deviceCount        : GroupMembership.countByDeviceGroup(group),
                roleCount          : GroupRoleAssignment.countByDeviceGroup(group),
        ]
    }

    private static Map assignment(GroupRoleAssignment assignment) {
        [
                id      : assignment.uuid,
                roleId  : assignment.role.uuid,
                roleName: assignment.role.name,
                displayName: assignment.role.displayName ?: assignment.role.name,
                repository: assignment.role.repository.name,
                missing : assignment.role.missing,
                position: assignment.position,
                enabled : assignment.enabled,
        ]
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
