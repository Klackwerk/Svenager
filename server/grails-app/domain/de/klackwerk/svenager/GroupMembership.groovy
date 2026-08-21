package de.klackwerk.svenager

class GroupMembership {

    Device device
    DeviceGroup deviceGroup
    Date dateCreated

    static constraints = {
        device unique: 'deviceGroup'
    }
}
