package de.klackwerk.svenager.tunnel

import de.klackwerk.svenager.RemoteSession
import de.klackwerk.svenager.RemoteSessionService
import de.klackwerk.svenager.RemoteSessionStatus
import de.klackwerk.svenager.security.DeviceTokenAuthFilter
import groovy.transform.CompileStatic
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * Validates the tunnel handshake: the browser's Origin must be this
 * instance (behind a TLS-terminating proxy that means the forwarded
 * scheme/host or the configured external URL, not what Tomcat sees), the
 * session must exist, be open and in the right state for the connecting
 * side, and an agent may only serve a tunnel for its own device. Auth
 * itself is enforced by the security filter chains.
 */
class TunnelHandshakeInterceptor implements HandshakeInterceptor {

    private final RemoteSessionService remoteSessionService
    private final boolean agentSide
    private final String externalUrl

    TunnelHandshakeInterceptor(RemoteSessionService remoteSessionService, boolean agentSide, String externalUrl) {
        this.remoteSessionService = remoteSessionService
        this.agentSide = agentSide
        this.externalUrl = externalUrl
    }

    @Override
    boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler handler, Map<String, Object> attributes) {
        if (!originAllowed(request, externalUrl)) {
            response.statusCode = HttpStatus.FORBIDDEN
            return false
        }
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

    /**
     * Same-origin check for browsers (agents send no Origin). Accepts the
     * configured external URL and the origin the proxy forwarded
     * (X-Forwarded-Proto/-Host), falling back to the plain request.
     */
    @CompileStatic
    static boolean originAllowed(ServerHttpRequest request, String externalUrl) {
        String origin = request.headers.getOrigin()
        if (!origin) {
            return true
        }
        Set<String> allowed = [] as Set<String>
        if (externalUrl) {
            allowed << normalizeOrigin(externalUrl)
        }
        String proto = firstValue(request, 'X-Forwarded-Proto') ?: request.URI.scheme
        String host = firstValue(request, 'X-Forwarded-Host') ?: request.headers.getFirst('Host')
        if (host) {
            allowed << normalizeOrigin("${proto}://${host}".toString())
        }
        normalizeOrigin(origin) in allowed
    }

    private static String firstValue(ServerHttpRequest request, String header) {
        request.headers.getFirst(header)?.tokenize(',')?.first()?.trim() ?: null
    }

    /** scheme://host[:port] lower-cased, default ports and paths dropped. */
    private static String normalizeOrigin(String value) {
        try {
            URI uri = new URI(value.trim())
            String scheme = uri.scheme?.toLowerCase()
            int port = uri.port
            if ((scheme == 'https' && port == 443) || (scheme == 'http' && port == 80)) {
                port = -1
            }
            return "${scheme}://${uri.host?.toLowerCase()}${port == -1 ? '' : ':' + port}".toString()
        } catch (URISyntaxException ignored) {
            return value.trim().toLowerCase()
        }
    }

    @Override
    void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                        WebSocketHandler handler, Exception exception) {
    }
}
