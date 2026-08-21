package de.klackwerk.svenager

class EnrollmentToken {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String label
    String tokenHash
    int maxUses = 1
    int usedCount = 0
    Date expiresAt
    boolean revoked = false
    String createdBy
    Date dateCreated

    /** Devices enrolled with this token join these groups immediately. */
    static hasMany = [targetGroups: DeviceGroup]

    static constraints = {
        uuid unique: true
        label blank: false, maxSize: 190
        tokenHash unique: true
        maxUses min: 1
        usedCount min: 0
        expiresAt nullable: true
        createdBy nullable: true
    }

    boolean isUsable() {
        !revoked && usedCount < maxUses && (expiresAt == null || expiresAt.after(new Date()))
    }
}
