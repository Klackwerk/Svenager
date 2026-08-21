package de.klackwerk.svenager.tunnel

import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.SubProtocolCapable
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.BinaryWebSocketHandler

/**
 * Server end of the browser's noVNC socket. noVNC requests the 'binary'
 * subprotocol and browsers abort the connection unless the server echoes a
 * requested subprotocol back — so it must be advertised here.
 */
class ViewerTunnelHandler extends BinaryWebSocketHandler implements SubProtocolCapable {

    @Override
    List<String> getSubProtocols() {
        ['binary']
    }

    private final TunnelBroker broker

    ViewerTunnelHandler(TunnelBroker broker) {
        this.broker = broker
    }

    @Override
    void afterConnectionEstablished(WebSocketSession session) {
        broker.viewerOpened(uuid(session), session)
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        broker.toAgent(uuid(session), message)
    }

    @Override
    void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broker.viewerClosed(uuid(session))
    }

    @Override
    void handleTransportError(WebSocketSession session, Throwable exception) {
        broker.viewerClosed(uuid(session))
    }

    private static String uuid(WebSocketSession session) {
        session.attributes.sessionUuid as String
    }
}
