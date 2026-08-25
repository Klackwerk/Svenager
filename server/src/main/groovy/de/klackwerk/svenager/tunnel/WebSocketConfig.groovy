package de.klackwerk.svenager.tunnel

import de.klackwerk.svenager.RemoteSessionService
import grails.core.GrailsApplication
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
    private final GrailsApplication grailsApplication

    WebSocketConfig(TunnelBroker broker, RemoteSessionService remoteSessionService,
                    GrailsApplication grailsApplication) {
        this.broker = broker
        this.remoteSessionService = remoteSessionService
        this.grailsApplication = grailsApplication
    }

    @Override
    void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Grails' UrlMappingsHandlerMapping runs at order -5 and would answer
        // the handshake with a 404 — the tunnel endpoints must match first.
        registry.order = -10
        // Spring's own origin check compares against the URL Tomcat sees,
        // which is wrong behind a TLS-terminating proxy; the interceptor
        // does a forwarded-header-aware check instead.
        String externalUrl = grailsApplication.config.getProperty('svenager.externalUrl', String, '')
        registry.addHandler(new AgentTunnelHandler(broker), '/api/v1/agent/tunnel/*')
                .setAllowedOrigins('*')
                .addInterceptors(new TunnelHandshakeInterceptor(remoteSessionService, true, externalUrl))
        registry.addHandler(new ViewerTunnelHandler(broker), '/api/v1/ui/vnc/*')
                .setAllowedOrigins('*')
                .addInterceptors(new TunnelHandshakeInterceptor(remoteSessionService, false, externalUrl))
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
