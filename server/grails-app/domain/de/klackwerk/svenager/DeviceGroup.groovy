package de.klackwerk.svenager

class DeviceGroup {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String name
    String description
    /** Overrides the global agent poll interval; null keeps the default. */
    Integer pollIntervalSeconds
    /** Overrides how long a device may be silent before an offline alert. */
    Integer offlineAlertSeconds
    Date dateCreated

    static constraints = {
        uuid unique: true
        name unique: true, blank: false, maxSize: 190
        description nullable: true, maxSize: 1000
        pollIntervalSeconds nullable: true, min: 10, max: 86400
        offlineAlertSeconds nullable: true, min: 60, max: 604800
    }
}
