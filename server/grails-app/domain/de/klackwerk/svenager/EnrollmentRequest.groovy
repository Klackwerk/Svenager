package de.klackwerk.svenager

/**
 * A device asking to join without an enrollment token (e.g. cloned from a
 * pre-configured image). Identified by a stable, device-generated request id
 * (machine id); an admin approves or denies it in the UI.
 */
class EnrollmentRequest {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String requestId
    String hostname
    String factsJson
    EnrollmentRequestStatus status = EnrollmentRequestStatus.PENDING
    Date dateCreated
    Date lastSeenAt
    String decidedBy
    Date decidedAt
    Device device

    static constraints = {
        uuid unique: true
        requestId unique: true, maxSize: 128
        hostname nullable: true
        factsJson nullable: true
        lastSeenAt nullable: true
        decidedBy nullable: true
        decidedAt nullable: true
        device nullable: true
    }

    static mapping = {
        factsJson type: 'text'
        status enumType: 'string'
    }
}
