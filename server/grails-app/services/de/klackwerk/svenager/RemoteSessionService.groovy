package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput

/**
 * Lifecycle and audit trail of remote-view sessions. Byte piping between the
 * agent and the browser lives in TunnelBroker; this service owns the state.
 */
@Transactional
class RemoteSessionService {

    static final int DEFAULT_MAX_SECONDS = 900
    static final List<RemoteSessionStatus> OPEN_STATUSES =
            [RemoteSessionStatus.PENDING, RemoteSessionStatus.AGENT_CONNECTED, RemoteSessionStatus.ACTIVE]

    GrailsApplication grailsApplication

    /**
     * Creates a session plus its OPEN_TUNNEL job. Every viewer gets their
     * own session/tunnel (an RFB connection cannot be shared), so several
     * operators can watch one device side by side; only the requester's own
     * not-yet-attached session is reused (e.g. a double-mounted UI).
     */
    RemoteSession open(Device device, String requestedBy, RemoteSessionKind kind = RemoteSessionKind.VNC) {
        // Serialize concurrent opens per device (e.g. a double-mounted UI),
        // or both would pass the reuse check and create two sessions.
        try {
            Device.lock(device.id)
        } catch (UnsupportedOperationException ignored) {
            // the in-memory unit-test datastore has no row locks
        }
        expireStale()
        RemoteSession existing = RemoteSession.findByDeviceAndRequestedByAndKindAndStatusInList(device, requestedBy,
                kind, [RemoteSessionStatus.PENDING, RemoteSessionStatus.AGENT_CONNECTED])
        if (existing) {
            return existing
        }
        RemoteSession session = new RemoteSession(
                device: device,
                requestedBy: requestedBy,
                kind: kind,
                expiresAt: new Date(System.currentTimeMillis() + maxSessionSeconds * 1000L),
        ).save(failOnError: true)
        Map payload = [sessionId: session.uuid]
        if (kind == RemoteSessionKind.SHELL) {
            payload.shell = true
        } else {
            payload.vncPort = vncPort
        }
        new Job(device: device, type: JobType.OPEN_TUNNEL, triggeredBy: requestedBy,
                payloadJson: JsonOutput.toJson(payload))
                .save(failOnError: true)
        session
    }

    RemoteSession find(String uuid) {
        expireStale()
        RemoteSession.findByUuid(uuid)
    }

    /** Detached snapshot for the WS handshake (usable outside a session). */
    Map handshakeInfo(String uuid) {
        expireStale()
        RemoteSession session = RemoteSession.findByUuid(uuid)
        session == null ? null : [status: session.status, deviceId: session.device.id, expiresAt: session.expiresAt]
    }

    void agentConnected(String uuid) {
        RemoteSession session = RemoteSession.findByUuid(uuid)
        if (session?.status == RemoteSessionStatus.PENDING) {
            session.status = RemoteSessionStatus.AGENT_CONNECTED
            session.agentConnectedAt = new Date()
            session.save(failOnError: true)
        }
    }

    void viewerConnected(String uuid) {
        RemoteSession session = RemoteSession.findByUuid(uuid)
        if (session?.status == RemoteSessionStatus.AGENT_CONNECTED) {
            session.status = RemoteSessionStatus.ACTIVE
            session.viewerConnectedAt = new Date()
            session.save(failOnError: true)
        }
    }

    /** Terminal transition; also cancels a not-yet-delivered OPEN_TUNNEL job. */
    void close(String uuid, String reason) {
        RemoteSession session = RemoteSession.findByUuid(uuid)
        if (session == null || session.status == RemoteSessionStatus.CLOSED) {
            return
        }
        session.status = RemoteSessionStatus.CLOSED
        session.closedAt = new Date()
        session.closeReason = reason
        session.save(failOnError: true)
        Job.findAllByDeviceAndTypeAndStatus(session.device, JobType.OPEN_TUNNEL, JobStatus.PENDING)
                .findAll { it.payloadJson?.contains(uuid) }
                .each { Job job ->
                    job.status = JobStatus.CANCELLED
                    job.error = "Remote session closed before delivery: ${reason}".toString()
                    job.finishedAt = new Date()
                    job.save(failOnError: true)
                }
    }

    /**
     * Tunnel pairings live in memory, so sessions that were open when the
     * server went down are unusable after a restart — close them at boot.
     */
    void closeAllOpen(String reason) {
        RemoteSession.findAllByStatusInList(OPEN_STATUSES).each { close(it.uuid, reason) }
    }

    /** Marks open sessions past their time limit as closed. */
    void expireStale() {
        RemoteSession.findAllByStatusInListAndExpiresAtLessThan(OPEN_STATUSES, new Date())
                .each { close(it.uuid, 'time limit reached') }
    }

    List<RemoteSession> recentFor(Device device, int max = 20) {
        RemoteSession.findAllByDevice(device, [sort: 'dateCreated', order: 'desc', max: max])
    }

    int getMaxSessionSeconds() {
        grailsApplication.config.getProperty('svenager.remote.sessionMaxSeconds', Integer, DEFAULT_MAX_SECONDS)
    }

    int getVncPort() {
        grailsApplication.config.getProperty('svenager.remote.vncPort', Integer, 5900)
    }
}
