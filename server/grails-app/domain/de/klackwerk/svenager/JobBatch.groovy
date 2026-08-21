package de.klackwerk.svenager

/** One group fan-out of apply jobs, for roll-up progress in the UI. */
class JobBatch {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String triggeredBy
    DeviceGroup deviceGroup
    /** null = plain fan-out; CANARY = one device first; FULL = continued. */
    String stage
    Date dateCreated

    static constraints = {
        uuid unique: true
        triggeredBy nullable: true
        deviceGroup nullable: true
        stage nullable: true, inList: ['CANARY', 'FULL']
    }
}
