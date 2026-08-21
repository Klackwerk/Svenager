package de.klackwerk.svenager

class Job {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    Device device
    /** The group fan-out this job belongs to, if it was part of one. */
    JobBatch batch
    JobType type = JobType.APPLY_CONFIG
    /** The composed play spec (without secret values), for audit. */
    String payloadJson
    /** SHA-256 of the delivered spec (plays + extraVars) for drift detection. */
    String specHash
    JobStatus status = JobStatus.PENDING
    /** 1 for operator/drift jobs; auto-retries count up to the bound. */
    int attempt = 1
    Integer exitCode
    String error
    String triggeredBy
    /** Not delivered to the device before this time; null = immediately. */
    Date runAfter
    Date dateCreated
    Date deliveredAt
    Date startedAt
    Date finishedAt

    static constraints = {
        uuid unique: true
        batch nullable: true
        payloadJson nullable: true
        specHash nullable: true
        exitCode nullable: true
        error nullable: true
        triggeredBy nullable: true
        runAfter nullable: true
        deliveredAt nullable: true
        startedAt nullable: true
        finishedAt nullable: true
    }

    static mapping = {
        payloadJson type: 'text'
        error type: 'text'
        type enumType: 'string'
        status enumType: 'string'
    }
}
