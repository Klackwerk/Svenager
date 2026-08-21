package de.klackwerk.svenager.tunnel

import de.klackwerk.svenager.RemoteSessionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

/**
 * Registers the two tunnel endpoints. Authentication comes from the existing
 * security filter chains: the agent path requires a device bearer token, the
 * viewer path the operator's session cookie.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final TunnelBroker broker
    private final RemoteSessionService remoteSessionService

    WebSocketConfig(TunnelBroker broker, RemoteSessionService remoteSessionService) {
        this.broker = broker
        this.remoteSessionService = remoteSessionService
    }

    @Override
    void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Grails' UrlMappingsHandlerMapping runs at order -5 and would answer
        // the handshake with a 404 — the tunnel endpoints must match first.
        registry.order = -10
        registry.addHandler(new AgentTunnelHandler(broker), '/api/v1/agent/tunnel/*')
                .addInterceptors(new TunnelHandshakeInterceptor(remoteSessionService, true))
        registry.addHandler(new ViewerTunnelHandler(broker), '/api/v1/ui/vnc/*')
                .addInterceptors(new TunnelHandshakeInterceptor(remoteSessionService, false))
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean()
        container.maxBinaryMessageBufferSize = 256 * 1024
        container.maxTextMessageBufferSize = 64 * 1024
        container.maxSessionIdleTimeout = 10 * 60_000L
        container
    }
}
