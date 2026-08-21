package de.klackwerk.svenager.tunnel

import de.klackwerk.svenager.RemoteSession
import de.klackwerk.svenager.RemoteSessionService
import de.klackwerk.svenager.RemoteSessionStatus
import de.klackwerk.svenager.security.DeviceTokenAuthFilter
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * Validates the tunnel handshake: the session must exist, be open and in the
 * right state for the connecting side, and an agent may only serve a tunnel
 * for its own device. Auth itself is enforced by the security filter chains.
 */
class TunnelHandshakeInterceptor implements HandshakeInterceptor {

    private final RemoteSessionService remoteSessionService
    private final boolean agentSide

    TunnelHandshakeInterceptor(RemoteSessionService remoteSessionService, boolean agentSide) {
        this.remoteSessionService = remoteSessionService
        this.agentSide = agentSide
    }

    @Override
    boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler handler, Map<String, Object> attributes) {
        String uuid = request.URI.path.tokenize('/').last()
        Map info = remoteSessionService.handshakeInfo(uuid)
        if (info == null || !allowed(info, request)) {
            response.statusCode = HttpStatus.FORBIDDEN
            return false
        }
        attributes.sessionUuid = uuid
        attributes.expiresAt = info.expiresAt
        true
    }

    private boolean allowed(Map info, ServerHttpRequest request) {
        if (agentSide) {
            Long deviceId = ((ServletServerHttpRequest) request).servletRequest
                    .getAttribute(DeviceTokenAuthFilter.DEVICE_ID_ATTRIBUTE) as Long
            return info.status == RemoteSessionStatus.PENDING && info.deviceId == deviceId
        }
        info.status == RemoteSessionStatus.AGENT_CONNECTED
    }

    @Override
    void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                        WebSocketHandler handler, Exception exception) {
    }
}
