package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class RemoteSessionServiceSpec extends Specification implements ServiceUnitTest<RemoteSessionService>, DataTest {

    void setupSpec() {
        mockDomains(Device, RemoteSession, Job)
    }

    private Device device() {
        new Device(hostname: 'kiosk', tokenHash: UUID.randomUUID().toString()).save(failOnError: true)
    }

    void "open creates an audited session plus its OPEN_TUNNEL job"() {
        given:
        Device d = device()

        when:
        RemoteSession session = service.open(d, 'admin')

        then:
        session.status == RemoteSessionStatus.PENDING
        session.requestedBy == 'admin'
        session.expiresAt.time > System.currentTimeMillis()
        Job.count() == 1
        Job.first().type == JobType.OPEN_TUNNEL
        Job.first().payloadJson.contains(session.uuid)
        Job.first().payloadJson.contains('5900')
    }

    void "openShell creates a SHELL session whose job asks for a shell, not VNC"() {
        given:
        Device d = device()

        when:
        RemoteSession session = service.open(d, 'admin', RemoteSessionKind.SHELL)

        then:
        session.kind == RemoteSessionKind.SHELL
        Job.count() == 1
        Job.first().type == JobType.OPEN_TUNNEL
        Job.first().payloadJson.contains(session.uuid)
        Job.first().payloadJson.contains('"shell":true')
        !Job.first().payloadJson.contains('5900')
    }

    void "a VNC and a shell session for one viewer do not collide"() {
        given:
        Device d = device()

        when:
        RemoteSession vnc = service.open(d, 'admin')
        RemoteSession shell = service.open(d, 'admin', RemoteSessionKind.SHELL)

        then: 'different sessions, one job each'
        vnc.uuid != shell.uuid
        vnc.kind == RemoteSessionKind.VNC
        shell.kind == RemoteSessionKind.SHELL
        Job.count() == 2

        and: 'each kind reuses its own pending session'
        service.open(d, 'admin').uuid == vnc.uuid
        service.open(d, 'admin', RemoteSessionKind.SHELL).uuid == shell.uuid
        Job.count() == 2
    }

    void "open reuses the device's existing open session"() {
        given:
        Device d = device()
        RemoteSession first = service.open(d, 'admin')

        expect:
        service.open(d, 'admin').uuid == first.uuid
        Job.count() == 1
    }

    void "every viewer gets their own session and tunnel"() {
        given:
        Device d = device()
        RemoteSession first = service.open(d, 'admin')

        expect: 'a second operator is not glued onto the first tunnel'
        service.open(d, 'operator').uuid != first.uuid
        Job.count() == 2
    }

    void "a session already viewed elsewhere is not reused"() {
        given: 'the same user watches in another tab'
        Device d = device()
        RemoteSession first = service.open(d, 'admin')
        service.agentConnected(first.uuid)
        service.viewerConnected(first.uuid)

        expect:
        service.open(d, 'admin').uuid != first.uuid
    }

    void "close is terminal, idempotent and cancels an undelivered tunnel job"() {
        given:
        Device d = device()
        RemoteSession session = service.open(d, 'admin')

        when:
        service.close(session.uuid, 'viewer disconnected')

        then:
        session.status == RemoteSessionStatus.CLOSED
        session.closeReason == 'viewer disconnected'
        session.closedAt != null
        Job.first().status == JobStatus.CANCELLED

        when: 'closed again with a different reason'
        service.close(session.uuid, 'other')

        then: 'the first reason wins'
        session.closeReason == 'viewer disconnected'
    }

    void "sessions past their time limit are expired"() {
        given:
        Device d = device()
        RemoteSession session = new RemoteSession(device: d,
                expiresAt: new Date(System.currentTimeMillis() - 1000)).save(failOnError: true, flush: true)

        when:
        RemoteSession found = service.find(session.uuid)

        then:
        found.status == RemoteSessionStatus.CLOSED
        found.closeReason == 'time limit reached'

        and: 'a new open call starts a fresh session'
        service.open(d, 'admin').uuid != session.uuid
    }

    void "a restart closes every session that was open"() {
        given:
        Device d = device()
        RemoteSession open = service.open(d, 'admin')
        RemoteSession done = new RemoteSession(device: d, status: RemoteSessionStatus.CLOSED,
                closeReason: 'viewer disconnected',
                expiresAt: new Date(System.currentTimeMillis() + 600_000)).save(failOnError: true)

        when:
        service.closeAllOpen('server restarted')

        then:
        open.status == RemoteSessionStatus.CLOSED
        open.closeReason == 'server restarted'
        done.closeReason == 'viewer disconnected'
    }

    void "connection events walk the session through its states"() {
        given:
        Device d = device()
        RemoteSession session = service.open(d, 'admin')

        when:
        service.agentConnected(session.uuid)

        then:
        session.status == RemoteSessionStatus.AGENT_CONNECTED
        session.agentConnectedAt != null

        when:
        service.viewerConnected(session.uuid)

        then:
        session.status == RemoteSessionStatus.ACTIVE
        session.viewerConnectedAt != null

        and: 'events on a closed session are ignored'
        service.close(session.uuid, 'done')
        service.agentConnected(session.uuid)
        session.status == RemoteSessionStatus.CLOSED
    }
}
