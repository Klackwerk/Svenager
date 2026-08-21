package de.klackwerk.svenager.tunnel

import de.klackwerk.svenager.RemoteSessionService
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Component
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pairs the agent's reverse tunnel with the browser's noVNC socket and pipes
 * binary frames between them. Enforces the session time limit and reports
 * every lifecycle transition to RemoteSessionService for the audit trail.
 */
@Slf4j
@Component
class TunnelBroker {

    /** A slow peer may buffer at most this much before the tunnel is killed. */
    static final int SEND_BUFFER_SIZE_BYTES = 4 * 1024 * 1024
    static final int SEND_TIME_LIMIT_MS = 20_000
    /** VNC servers greet before a viewer exists — hold that much at most. */
    static final int MAX_BACKLOG_BYTES = 64 * 1024

    RemoteSessionService remoteSessionService

    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>()
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread t = new Thread(r, 'tunnel-expiry')
        t.daemon = true
        t
    }

    TunnelBroker(RemoteSessionService remoteSessionService) {
        this.remoteSessionService = remoteSessionService
    }

    private static class Tunnel {
        volatile WebSocketSession agent
        volatile WebSocketSession viewer
        volatile ScheduledFuture<?> expiry
        /** Agent frames sent before the viewer attached (guarded by itself). */
        final List<BinaryMessage> backlog = []
    }

    void agentOpened(String sessionUuid, WebSocketSession ws, Date expiresAt) {
        Tunnel tunnel = tunnels.computeIfAbsent(sessionUuid) { new Tunnel() }
        if (tunnel.agent != null) {
            quietClose(ws, CloseStatus.POLICY_VIOLATION)
            return
        }
        tunnel.agent = decorate(ws)
        long delay = Math.max(expiresAt.time - System.currentTimeMillis(), 0L)
        tunnel.expiry = scheduler.schedule(
                { close(sessionUuid, 'time limit reached') }, delay, TimeUnit.MILLISECONDS)
        remoteSessionService.agentConnected(sessionUuid)
        log.info('remote session {}: device tunnel connected', sessionUuid)
    }

    void viewerOpened(String sessionUuid, WebSocketSession ws) {
        Tunnel tunnel = tunnels[sessionUuid]
        if (tunnel == null || tunnel.agent == null || tunnel.viewer != null) {
            quietClose(ws, CloseStatus.POLICY_VIOLATION)
            return
        }
        synchronized (tunnel.backlog) {
            tunnel.viewer = decorate(ws)
            tunnel.backlog.each { relay(sessionUuid, tunnel.viewer, it) }
            tunnel.backlog.clear()
        }
        remoteSessionService.viewerConnected(sessionUuid)
        log.info('remote session {}: viewer connected', sessionUuid)
    }

    void toViewer(String sessionUuid, BinaryMessage message) {
        Tunnel tunnel = tunnels[sessionUuid]
        if (tunnel == null) {
            return
        }
        boolean overflow = false
        synchronized (tunnel.backlog) {
            if (tunnel.viewer == null) {
                // The VNC server talks first (RFB greeting) — keep its opening
                // bytes until the viewer attaches instead of dropping them.
                tunnel.backlog << message
                overflow = tunnel.backlog.sum { (it as BinaryMessage).payloadLength } as int > MAX_BACKLOG_BYTES
            } else {
                relay(sessionUuid, tunnel.viewer, message)
            }
        }
        if (overflow) {
            close(sessionUuid, 'connection error')
        }
    }

    void toAgent(String sessionUuid, BinaryMessage message) {
        relay(sessionUuid, tunnels[sessionUuid]?.agent, message)
    }

    private void relay(String sessionUuid, WebSocketSession target, BinaryMessage message) {
        if (target == null) {
            return // peer not attached yet — drop (VNC resyncs via update requests)
        }
        try {
            target.sendMessage(message)
        } catch (Exception e) {
            log.warn('remote session {}: relay failed: {}', sessionUuid, e.message)
            close(sessionUuid, 'connection error')
        }
    }

    void agentClosed(String sessionUuid) {
        if (tunnels.containsKey(sessionUuid)) {
            close(sessionUuid, 'device disconnected')
        }
    }

    void viewerClosed(String sessionUuid) {
        if (tunnels[sessionUuid]?.viewer != null) {
            close(sessionUuid, 'viewer disconnected')
        }
    }

    /** Tears down both legs (if any) and closes the audited session. */
    void close(String sessionUuid, String reason) {
        Tunnel tunnel = tunnels.remove(sessionUuid)
        if (tunnel != null) {
            tunnel.expiry?.cancel(false)
            quietClose(tunnel.agent, CloseStatus.NORMAL)
            quietClose(tunnel.viewer, CloseStatus.NORMAL)
            log.info('remote session {}: closed ({})', sessionUuid, reason)
        }
        remoteSessionService.close(sessionUuid, reason)
    }

    private static WebSocketSession decorate(WebSocketSession ws) {
        new ConcurrentWebSocketSessionDecorator(ws, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_BYTES)
    }

    private static void quietClose(WebSocketSession ws, CloseStatus status) {
        try {
            ws?.close(status)
        } catch (IOException ignored) {
        }
    }
}
