package de.klackwerk.svenager

import grails.gorm.transactions.Transactional

/** Aggregates for the group-based dashboard. */
@Transactional(readOnly = true)
class DashboardService {

    static final int SUCCESS_WINDOW_DAYS = 7

    CheckinService checkinService
    GroupService groupService

    Map overview() {
        List<Device> devices = Device.list()
        Map<Long, Boolean> onlineById = devices.collectEntries { [it.id, checkinService.isOnline(it)] }
        Date windowStart = new Date(System.currentTimeMillis() - SUCCESS_WINDOW_DAYS * 24 * 3600_000L)

        List<Map> groups = DeviceGroup.list(sort: 'name').collect { DeviceGroup group ->
            groupSummary(group, onlineById, windowStart)
        }

        long grouped = (GroupMembership.createCriteria().list {
            projections { distinct('device') }
        } as List).size()

        [
                devices  : [
                        total  : devices.size(),
                        online : onlineById.values().count { it },
                        offline: onlineById.values().count { !it },
                        ungrouped: devices.size() - grouped,
                ],
                jobs     : jobStats(windowStart),
                repos    : [
                        total : AnsibleRepository.count(),
                        errors: AnsibleRepository.countBySyncStatus(RepoSyncStatus.ERROR),
                        neverSynced: AnsibleRepository.countBySyncStatus(RepoSyncStatus.NEVER),
                ],
                groups   : groups,
                windowDays: SUCCESS_WINDOW_DAYS,
        ]
    }

    private Map groupSummary(DeviceGroup group, Map<Long, Boolean> onlineById, Date windowStart) {
        List<Device> members = groupService.membersOf(group)
        Map jobs = members ? jobStats(windowStart, members) : [succeeded: 0, failed: 0, active: 0]
        [
                id           : group.uuid,
                name         : group.name,
                description  : group.description,
                deviceCount  : members.size(),
                onlineCount  : members.count { onlineById[it.id] },
                roleCount    : GroupRoleAssignment.countByDeviceGroup(group),
                lastContactAt: members*.lastContactAt.findAll()?.max()?.toInstant()?.toString(),
                lastJobAt    : members*.lastJobAt.findAll()?.max()?.toInstant()?.toString(),
                jobs         : jobs,
        ]
    }

    /** Finished/active job counts, optionally restricted to the given devices. */
    private Map jobStats(Date windowStart, List<Device> devices = null) {
        List<Job> jobs = Job.createCriteria().list {
            if (devices != null) {
                'in'('device', devices)
            }
            or {
                gt('dateCreated', windowStart)
                'in'('status', [JobStatus.PENDING, JobStatus.DELIVERED, JobStatus.RUNNING])
            }
        } as List<Job>
        [
                succeeded: jobs.count { it.status == JobStatus.SUCCEEDED },
                failed   : jobs.count { it.status in [JobStatus.FAILED, JobStatus.TIMED_OUT] },
                active   : jobs.count { it.status in [JobStatus.PENDING, JobStatus.DELIVERED, JobStatus.RUNNING] },
        ]
    }
}
