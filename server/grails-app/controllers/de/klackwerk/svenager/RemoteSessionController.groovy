package de.klackwerk.svenager

import de.klackwerk.svenager.tunnel.TunnelBroker
import grails.converters.JSON
import org.springframework.security.core.context.SecurityContextHolder

/** UI endpoints for remote-view (VNC) sessions and their audit trail. */
class RemoteSessionController {

    static allowedMethods = [open: 'POST', openShell: 'POST', show: 'GET', close: 'DELETE', forDevice: 'GET']

    RemoteSessionService remoteSessionService
    TunnelBroker tunnelBroker
    AccessService accessService

    /** 404 for unknown AND out-of-scope devices — no existence leaks. */
    private Device visibleDevice(String id) {
        Device device = Device.findByUuid(id)
        (device != null && accessService.canSeeDevice(device)) ? device : null
    }

    /** POST /devices/{id}/remote-session — opens (or reuses) a session. */
    def open(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        RemoteSession session = remoteSessionService.open(device, currentUsername())
        response.status = 201
        render(summarize(session) as JSON)
    }

    /** POST /devices/{id}/shell-session — opens (or reuses) a shell session. */
    def openShell(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        RemoteSession session = remoteSessionService.open(device, currentUsername(), RemoteSessionKind.SHELL)
        response.status = 201
        render(summarize(session) as JSON)
    }

    def show(String id) {
        RemoteSession session = remoteSessionService.find(id)
        if (session != null && !accessService.canSeeDevice(session.device)) {
            session = null
        }
        if (session == null) {
            respondError(404, 'remote session not found')
            return
        }
        render(summarize(session) as JSON)
    }

    def close(String id) {
        RemoteSession session = remoteSessionService.find(id)
        if (session != null && !accessService.canSeeDevice(session.device)) {
            session = null
        }
        if (session == null) {
            respondError(404, 'remote session not found')
            return
        }
        tunnelBroker.close(id, "closed by ${currentUsername()}")
        render(summarize(remoteSessionService.find(id)) as JSON)
    }

    /** GET /devices/{id}/remote-sessions — recent sessions for the audit view. */
    def forDevice(String id) {
        Device device = visibleDevice(id)
        if (device == null) {
            respondError(404, 'device not found')
            return
        }
        render(remoteSessionService.recentFor(device).collect { summarize(it) } as JSON)
    }

    static Map summarize(RemoteSession session) {
        [
                sessionId        : session.uuid,
                deviceId         : session.device.uuid,
                hostname         : session.device.hostname,
                status           : session.status.name(),
                kind             : session.kind.name(),
                requestedBy      : session.requestedBy,
                createdAt        : session.dateCreated?.toInstant()?.toString(),
                expiresAt        : session.expiresAt?.toInstant()?.toString(),
                agentConnectedAt : session.agentConnectedAt?.toInstant()?.toString(),
                viewerConnectedAt: session.viewerConnectedAt?.toInstant()?.toString(),
                closedAt         : session.closedAt?.toInstant()?.toString(),
                closeReason      : session.closeReason,
                wsPath           : "/api/v1/ui/vnc/${session.uuid}".toString(),
        ]
    }

    private static String currentUsername() {
        SecurityContextHolder.context?.authentication?.name
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
