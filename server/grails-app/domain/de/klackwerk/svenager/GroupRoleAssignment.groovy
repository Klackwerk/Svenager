package de.klackwerk.svenager

/** A role that all devices of a group execute, in `position` order. */
class GroupRoleAssignment {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    DeviceGroup deviceGroup
    DiscoveredRole role
    int position = 0
    boolean enabled = true
    Date dateCreated

    static constraints = {
        uuid unique: true
        role unique: 'deviceGroup'
        position min: 0
    }
}
