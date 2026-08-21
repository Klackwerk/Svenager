package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The full M5 loop over real sockets: open a remote session, agent dials the
 * reverse tunnel with its device token, the browser side attaches with its
 * login session, bytes flow both ways, closing audits the session.
 */
@Integration
class RemoteViewIntegrationSpec extends Specification {

    @Shared
    EnrollmentService enrollmentService

    ApiClient client
    PollingConditions eventually = new PollingConditions(timeout: 10)

    void setup() {
        client = new ApiClient(serverPort)
    }

    private Map enrollDevice() {
        String token = null
        EnrollmentToken.withNewTransaction {
            token = enrollmentService.createToken('remote-it', 1, null, 'spec').token
        }
        client.request('POST', '/api/v1/enroll', [enrollmentToken: token, hostname: 'remote-device']).body as Map
    }

    private static class CollectingListener implements WebSocket.Listener {
        final List<String> messages = new CopyOnWriteArrayList<>()
        final CountDownLatch closed = new CountDownLatch(1)

        @Override
        void onOpen(WebSocket ws) {
            ws.request(1)
        }

        @Override
        CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]
            data.get(bytes)
            messages << new String(bytes, 'UTF-8')
            ws.request(1)
            null
        }

        @Override
        CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.countDown()
            null
        }
    }

    private WebSocket connectAgent(String sessionId, String deviceToken) {
        agentListener = new CollectingListener()
        HttpClient.newHttpClient().newWebSocketBuilder()
                .header('Authorization', "Bearer ${deviceToken}")
                .buildAsync(URI.create("ws://localhost:${serverPort}/api/v1/agent/tunnel/${sessionId}"), agentListener)
                .join()
    }

    CollectingListener agentListener
    CollectingListener viewerListener

    void "remote view tunnels bytes end to end and audits the session"() {
        given: 'an enrolled device and a logged-in operator'
        Map enrolled = enrollDevice()
        client.login('admin', 'admin')

        when: 'the operator opens a remote session'
        def opened = client.request('POST', "/api/v1/devices/${enrolled.deviceId}/remote-session",
                [:], client.csrfHeader())

        then:
        opened.status == 201
        opened.body.status == 'PENDING'
        String sessionId = opened.body.sessionId

        when: 'the agent receives the tunnel job at check-in'
        def checkin = client.request('POST', '/api/v1/agent/checkin', [agentVersion: 'it'],
                [Authorization: "Bearer ${enrolled.deviceToken}".toString()])

        then:
        checkin.body.job.type == 'OPEN_TUNNEL'
        checkin.body.job.payload.sessionId == sessionId
        checkin.body.job.payload.maxSeconds > 0

        when: 'the agent dials the reverse tunnel'
        WebSocket agentWs = connectAgent(sessionId, enrolled.deviceToken as String)

        then:
        eventually.eventually {
            assert client.request('GET', "/api/v1/remote-sessions/${sessionId}").body.status == 'AGENT_CONNECTED'
        }

        when: 'the device VNC server talks first, before any viewer exists'
        agentWs.sendBinary(ByteBuffer.wrap('RFB-greeting'.bytes), true).join()

        and: 'the operator attaches the viewer socket, asking for noVNC\'s subprotocol'
        viewerListener = new CollectingListener()
        WebSocket viewerWs = client.webSocketBuilder()
                .subprotocols('binary')
                .buildAsync(URI.create("ws://localhost:${serverPort}/api/v1/ui/vnc/${sessionId}"), viewerListener)
                .join()

        then: 'the server accepts the subprotocol — browsers abort otherwise'
        viewerWs.subprotocol == 'binary'
        eventually.eventually {
            assert client.request('GET', "/api/v1/remote-sessions/${sessionId}").body.status == 'ACTIVE'
        }

        when: 'bytes flow both ways'
        agentWs.sendBinary(ByteBuffer.wrap('framebuffer'.bytes), true).join()
        viewerWs.sendBinary(ByteBuffer.wrap('keystroke'.bytes), true).join()

        then: 'the buffered greeting arrives first, then the live frames'
        eventually.eventually {
            assert viewerListener.messages == ['RFB-greeting', 'framebuffer']
            assert agentListener.messages == ['keystroke']
        }

        when: 'the operator ends the session'
        def closed = client.request('DELETE', "/api/v1/remote-sessions/${sessionId}", null, client.csrfHeader())

        then: 'the audit trail is complete and both sockets are closed'
        closed.body.status == 'CLOSED'
        closed.body.closeReason == 'closed by admin'
        closed.body.requestedBy == 'admin'
        closed.body.agentConnectedAt != null
        closed.body.viewerConnectedAt != null
        agentListener.closed.await(5, TimeUnit.SECONDS)
        viewerListener.closed.await(5, TimeUnit.SECONDS)

        and: 'the session shows up in the device audit list'
        with(client.request('GET', "/api/v1/devices/${enrolled.deviceId}/remote-sessions").body.first()) {
            it.sessionId == sessionId
            it.status == 'CLOSED'
        }
    }

    void "tunnel endpoints reject the wrong callers"() {
        given: 'a session for one device'
        Map enrolled = enrollDevice()
        Map other = enrollDevice()
        client.login('admin', 'admin')
        String sessionId = client.request('POST', "/api/v1/devices/${enrolled.deviceId}/remote-session",
                [:], client.csrfHeader()).body.sessionId

        when: 'a different device tries to serve the tunnel'
        HttpClient.newHttpClient().newWebSocketBuilder()
                .header('Authorization', "Bearer ${other.deviceToken}")
                .buildAsync(URI.create("ws://localhost:${serverPort}/api/v1/agent/tunnel/${sessionId}"),
                        new CollectingListener())
                .join()

        then:
        thrown(Exception)

        when: 'an anonymous browser tries to attach'
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:${serverPort}/api/v1/ui/vnc/${sessionId}"),
                        new CollectingListener())
                .join()

        then:
        thrown(Exception)

        and: 'the session is untouched'
        client.request('GET', "/api/v1/remote-sessions/${sessionId}").body.status == 'PENDING'
    }
}
