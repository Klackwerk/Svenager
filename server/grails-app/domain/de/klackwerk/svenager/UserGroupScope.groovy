package de.klackwerk.svenager

/** One group a scoped user (User.allGroups == false) may access. */
class UserGroupScope {

    User user
    DeviceGroup deviceGroup

    static constraints = {
        deviceGroup unique: 'user'
    }
}
