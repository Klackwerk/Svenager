package de.klackwerk.svenager.tunnel

import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.BinaryWebSocketHandler

/** Server end of the agent's reverse tunnel: device VNC bytes in/out. */
class AgentTunnelHandler extends BinaryWebSocketHandler {

    private final TunnelBroker broker

    AgentTunnelHandler(TunnelBroker broker) {
        this.broker = broker
    }

    @Override
    void afterConnectionEstablished(WebSocketSession session) {
        broker.agentOpened(uuid(session), session, session.attributes.expiresAt as Date)
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        broker.toViewer(uuid(session), message)
    }

    @Override
    void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broker.agentClosed(uuid(session))
    }

    @Override
    void handleTransportError(WebSocketSession session, Throwable exception) {
        broker.agentClosed(uuid(session))
    }

    private static String uuid(WebSocketSession session) {
        session.attributes.sessionUuid as String
    }
}
