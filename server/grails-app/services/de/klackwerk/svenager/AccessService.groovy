package de.klackwerk.svenager

import grails.gorm.transactions.Transactional
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Group-scope visibility checks. Users with allGroups (all local accounts
 * by default, all admins, and SSO users without a device-group mapping)
 * see the whole fleet; scoped users see only their groups' devices.
 */
@Transactional(readOnly = true)
class AccessService {

    private User currentUser() {
        String name = SecurityContextHolder.context?.authentication?.name
        name ? User.findByUsername(name) : null
    }

    /** Fleet-wide access? Unknown principals (system, schedulers) are. */
    boolean fleetWide() {
        User user = currentUser()
        user == null || user.allGroups
    }

    boolean canSeeGroup(DeviceGroup group) {
        User user = currentUser()
        if (user == null || user.allGroups) {
            return true
        }
        UserGroupScope.countByUserAndDeviceGroup(user, group) > 0
    }

    boolean canSeeDevice(Device device) {
        User user = currentUser()
        if (user == null || user.allGroups) {
            return true
        }
        List<DeviceGroup> groups = UserGroupScope.findAllByUser(user)*.deviceGroup
        groups && GroupMembership.countByDeviceAndDeviceGroupInList(device, groups) > 0
    }

    boolean canSeeBatch(JobBatch batch) {
        batch.deviceGroup != null ? canSeeGroup(batch.deviceGroup) : fleetWide()
    }

    /** Visible device ids, or null when unrestricted. */
    Set<Long> visibleDeviceIds() {
        User user = currentUser()
        if (user == null || user.allGroups) {
            return null
        }
        List<DeviceGroup> groups = UserGroupScope.findAllByUser(user)*.deviceGroup
        groups ? (GroupMembership.findAllByDeviceGroupInList(groups)*.device*.id as Set<Long>)
                : ([] as Set<Long>)
    }

    /** Visible group ids, or null when unrestricted. */
    Set<Long> visibleGroupIds() {
        User user = currentUser()
        if (user == null || user.allGroups) {
            return null
        }
        UserGroupScope.findAllByUser(user)*.deviceGroup*.id as Set<Long>
    }
}
