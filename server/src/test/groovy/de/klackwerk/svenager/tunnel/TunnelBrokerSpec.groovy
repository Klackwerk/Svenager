package de.klackwerk.svenager.tunnel

import de.klackwerk.svenager.RemoteSessionService
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class TunnelBrokerSpec extends Specification {

    RemoteSessionService sessions = Mock()
    TunnelBroker broker = new TunnelBroker(sessions)

    private WebSocketSession ws() {
        Mock(WebSocketSession) { isOpen() >> true }
    }

    Date soon = new Date(System.currentTimeMillis() + 60_000)

    void "frames are relayed between agent and viewer once both are attached"() {
        given:
        WebSocketSession agent = ws()
        WebSocketSession viewer = ws()
        BinaryMessage down = new BinaryMessage('screen'.bytes)
        BinaryMessage up = new BinaryMessage('keys'.bytes)

        when:
        broker.agentOpened('s1', agent, soon)
        broker.viewerOpened('s1', viewer)
        broker.toViewer('s1', down)
        broker.toAgent('s1', up)

        then:
        1 * sessions.agentConnected('s1')
        1 * sessions.viewerConnected('s1')
        1 * viewer.sendMessage(down)
        1 * agent.sendMessage(up)
    }

    void "agent frames sent before the viewer attaches are buffered, not lost"() {
        given: 'the VNC server greets as soon as the agent leg is up'
        WebSocketSession agent = ws()
        WebSocketSession viewer = ws()
        BinaryMessage greeting = new BinaryMessage('RFB 003.008\n'.bytes)

        when:
        broker.agentOpened('s1', agent, soon)
        broker.toViewer('s1', greeting)
        broker.viewerOpened('s1', viewer)

        then: 'the greeting is delivered on attach'
        1 * viewer.sendMessage(greeting)
    }

    void "a viewer without a connected agent is rejected"() {
        given:
        WebSocketSession viewer = ws()

        when:
        broker.viewerOpened('nope', viewer)

        then:
        1 * viewer.close(CloseStatus.POLICY_VIOLATION)
        0 * sessions.viewerConnected(_)
    }

    void "a second agent connection for the same session is rejected"() {
        given:
        WebSocketSession first = ws()
        WebSocketSession second = ws()
        broker.agentOpened('s1', first, soon)

        when:
        broker.agentOpened('s1', second, soon)

        then:
        1 * second.close(CloseStatus.POLICY_VIOLATION)
    }

    void "a leaving viewer tears the whole session down"() {
        given:
        WebSocketSession agent = ws()
        WebSocketSession viewer = ws()
        broker.agentOpened('s1', agent, soon)
        broker.viewerOpened('s1', viewer)

        when:
        broker.viewerClosed('s1')

        then:
        1 * sessions.close('s1', 'viewer disconnected')
        1 * agent.close(_)
        1 * viewer.close(_)
    }

    void "a vanished agent closes the session"() {
        given:
        WebSocketSession agent = ws()
        broker.agentOpened('s1', agent, soon)

        when:
        broker.agentClosed('s1')

        then:
        1 * sessions.close('s1', 'device disconnected')
    }

    void "closing an unknown session still records the audit close"() {
        when:
        broker.close('ghost', 'closed by admin')

        then:
        1 * sessions.close('ghost', 'closed by admin')
    }

    void "the time limit closes a connected tunnel"() {
        given: 'the expiry callback runs on the scheduler thread'
        def expired = new java.util.concurrent.CountDownLatch(1)
        sessions.close('s1', 'time limit reached') >> { expired.countDown() }
        WebSocketSession agent = ws()

        when:
        broker.agentOpened('s1', agent, new Date())

        then:
        expired.await(3, java.util.concurrent.TimeUnit.SECONDS)
        new PollingConditions(timeout: 3).eventually {
            broker.@tunnels.isEmpty()
        }
    }
}
