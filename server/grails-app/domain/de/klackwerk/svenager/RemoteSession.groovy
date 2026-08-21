package de.klackwerk.svenager

/**
 * One remote-view (VNC) tunnel session. Rows are never deleted with the
 * device's history intact: they are the audit trail of who viewed which
 * device, when, for how long and why the session ended.
 */
class RemoteSession {

    String uuid = UUID.randomUUID().toString()
    Device device
    String requestedBy
    RemoteSessionStatus status = RemoteSessionStatus.PENDING
    Date dateCreated
    Date expiresAt
    Date agentConnectedAt
    Date viewerConnectedAt
    Date closedAt
    String closeReason

    static constraints = {
        uuid unique: true
        requestedBy nullable: true
        agentConnectedAt nullable: true
        viewerConnectedAt nullable: true
        closedAt nullable: true
        closeReason nullable: true
    }

    static mapping = {
        status enumType: 'string'
    }

    boolean isExpired() {
        expiresAt.time <= System.currentTimeMillis()
    }
}
