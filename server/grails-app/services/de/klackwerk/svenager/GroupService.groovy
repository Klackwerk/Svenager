package de.klackwerk.svenager

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Transactional
class GroupService {

    CryptoService cryptoService

    // --- membership -------------------------------------------------------

    void setDeviceGroups(Device device, List<Long> groupIds) {
        List<DeviceGroup> groups = groupIds ? DeviceGroup.getAll(groupIds).findAll() : []
        GroupMembership.findAllByDevice(device).each { GroupMembership membership ->
            if (!(membership.deviceGroup.id in groups*.id)) {
                membership.delete()
            }
        }
        groups.each { DeviceGroup group ->
            if (!GroupMembership.findByDeviceAndDeviceGroup(device, group)) {
                new GroupMembership(device: device, deviceGroup: group).save(failOnError: true)
            }
        }
    }

    void addDevice(DeviceGroup group, Device device) {
        if (!GroupMembership.findByDeviceAndDeviceGroup(device, group)) {
            new GroupMembership(device: device, deviceGroup: group).save(failOnError: true)
        }
    }

    void removeDevice(DeviceGroup group, Device device) {
        GroupMembership.findByDeviceAndDeviceGroup(device, group)?.delete()
    }

    List<Device> membersOf(DeviceGroup group) {
        GroupMembership.findAllByDeviceGroup(group)*.device.sort { it.hostname }
    }

    List<DeviceGroup> groupsOf(Device device) {
        GroupMembership.findAllByDevice(device)*.deviceGroup.sort { it.name }
    }

    void deleteGroup(DeviceGroup group) {
        GroupMembership.findAllByDeviceGroup(group)*.delete()
        GroupRoleAssignment.findAllByDeviceGroup(group)*.delete()
        ConfigVariable.findAllByDeviceGroup(group)*.delete()
        // Batches stay as job history; they just lose the group reference.
        JobBatch.findAllByDeviceGroup(group).each {
            it.deviceGroup = null
            it.save(failOnError: true)
        }
        SsoGroupMapping.findAllByDeviceGroup(group)*.delete()
        UserGroupScope.findAllByDeviceGroup(group)*.delete()
        EnrollmentToken.list().each { EnrollmentToken token ->
            if (token.targetGroups?.any { it.id == group.id }) {
                token.removeFromTargetGroups(group)
                token.save(failOnError: true)
            }
        }
        group.delete()
    }

    void deleteDevice(Device device) {
        GroupMembership.findAllByDevice(device)*.delete()
        ConfigVariable.findAllByDevice(device)*.delete()
        Job.findAllByDevice(device).each { Job job ->
            JobLogChunk.findAllByJob(job)*.delete()
            job.delete()
        }
        RemoteSession.findAllByDevice(device)*.delete()
        // Keep the audit row of how the device joined, drop the reference.
        EnrollmentRequest.findAllByDevice(device).each {
            it.device = null
            it.save(failOnError: true)
        }
        device.delete()
    }

    // --- role assignments ---------------------------------------------------

    List<GroupRoleAssignment> assignmentsOf(DeviceGroup group) {
        GroupRoleAssignment.findAllByDeviceGroup(group).sort { it.position }
    }

    /**
     * All roles that would run for these groups, in execution order: the base
     * roles of every involved repository first, then the enabled assignments.
     * Drives the auto-generated variable forms in the UI.
     */
    List<DiscoveredRole> effectiveRoles(List<DeviceGroup> groups) {
        List<DiscoveredRole> assigned = []
        groups.each { DeviceGroup group ->
            assignmentsOf(group).each { GroupRoleAssignment assignment ->
                if (assignment.enabled && !assignment.role.missing && !(assignment.role in assigned)) {
                    assigned << assignment.role
                }
            }
        }
        List<DiscoveredRole> result = []
        assigned*.repository.unique { it.id }.each { AnsibleRepository repo ->
            DiscoveredRole.findAllByRepositoryAndBaseRoleAndMissing(repo, true, false)
                    .sort { it.name }
                    .each { if (!(it in result)) result << it }
        }
        assigned.each { if (!(it in result)) result << it }
        result
    }

    GroupRoleAssignment assignRole(DeviceGroup group, DiscoveredRole role) {
        GroupRoleAssignment existing = GroupRoleAssignment.findByDeviceGroupAndRole(group, role)
        if (existing) {
            return existing
        }
        int nextPosition = (assignmentsOf(group)*.position.max() ?: -1) + 1
        new GroupRoleAssignment(deviceGroup: group, role: role, position: nextPosition).save(failOnError: true)
    }

    void unassignRole(DeviceGroup group, Long assignmentId) {
        GroupRoleAssignment assignment = GroupRoleAssignment.get(assignmentId)
        if (assignment?.deviceGroup?.id == group.id) {
            assignment.delete()
        }
    }

    /** Reorders assignments to match the given id order; unknown ids are ignored. */
    void reorderRoles(DeviceGroup group, List<Long> assignmentIds) {
        List<GroupRoleAssignment> assignments = assignmentsOf(group)
        int position = 0
        assignmentIds.each { Long id ->
            GroupRoleAssignment assignment = assignments.find { it.id == id }
            if (assignment) {
                assignment.position = position++
                assignment.save(failOnError: true)
            }
        }
    }

    // --- variables ----------------------------------------------------------

    /** Replaces the variable set of a scope with the given [name, value, secret] entries. */
    void replaceVariables(DeviceGroup group, Device device, List<Map> entries) {
        List<ConfigVariable> existing = group != null ?
                ConfigVariable.findAllByDeviceGroup(group) : ConfigVariable.findAllByDevice(device)
        Map<String, ConfigVariable> byName = existing.collectEntries { [it.name, it] }
        Set<String> keep = []

        entries.each { Map entry ->
            String name = entry.name as String
            keep << name
            ConfigVariable variable = byName[name] ?: new ConfigVariable(deviceGroup: group, device: device, name: name)
            boolean secret = entry.secret as boolean
            // An untouched secret arrives without a value — keep the stored one.
            if (secret && entry.value == null && variable.id != null) {
                variable.secret = true
            } else {
                String json = JsonOutput.toJson(entry.value)
                variable.valueJson = secret ? cryptoService.encrypt(json) : json
                variable.secret = secret
            }
            variable.save(failOnError: true)
        }
        existing.findAll { !(it.name in keep) }*.delete()
    }

    /** Variables of a scope for the UI: secret values are never returned. */
    List<Map> listVariables(DeviceGroup group, Device device) {
        List<ConfigVariable> variables = group != null ?
                ConfigVariable.findAllByDeviceGroup(group) : ConfigVariable.findAllByDevice(device)
        variables.sort { it.name }.collect { ConfigVariable variable ->
            [
                    name  : variable.name,
                    secret: variable.secret,
                    value : variable.secret ? null : new JsonSlurper().parseText(variable.valueJson),
            ]
        }
    }
}
