package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import groovy.json.JsonOutput
import spock.lang.Specification

class JobServiceSpec extends Specification implements ServiceUnitTest<JobService>, DataTest {

    GroupService groupService

    void setupSpec() {
        mockDomains(Device, DeviceGroup, GroupMembership, AnsibleRepository, DiscoveredRole,
                GroupRoleAssignment, ConfigVariable, Job, JobBatch, JobLogChunk, RemoteSession)
    }

    void setup() {
        groupService = new GroupService()
        groupService.cryptoService = Stub(CryptoService) {
            encrypt(_) >> { String s -> "enc:${s}".toString() }
            decrypt(_) >> { String s -> s.substring(4) }
        }
        service.groupService = groupService
        service.cryptoService = groupService.cryptoService
    }

    private Device device() {
        new Device(hostname: 'kiosk', tokenHash: UUID.randomUUID().toString()).save(failOnError: true)
    }

    private AnsibleRepository repo(String name) {
        new AnsibleRepository(name: name, gitUrl: "https://example.org/${name}.git", lastCommit: "commit-${name}")
                .save(failOnError: true)
    }

    private DiscoveredRole role(AnsibleRepository repository, String name, Map extra = [:]) {
        new DiscoveredRole([repository: repository, name: name] + extra).save(failOnError: true)
    }

    void "composeSpec orders roles, prepends base roles per repo and pins commits"() {
        given:
        Device d = device()
        AnsibleRepository repoA = repo('a')
        AnsibleRepository repoB = repo('b')
        DiscoveredRole base = role(repoA, 'svenager_base', [baseRole: true, userAssignable: false])
        DiscoveredRole kiosk = role(repoA, 'kiosk')
        DiscoveredRole banner = role(repoB, 'banner')
        DiscoveredRole gone = role(repoA, 'gone', [missing: true])

        DeviceGroup g1 = new DeviceGroup(name: 'alpha').save(failOnError: true)
        DeviceGroup g2 = new DeviceGroup(name: 'beta').save(failOnError: true)
        groupService.addDevice(g1, d)
        groupService.addDevice(g2, d)
        groupService.assignRole(g1, kiosk)
        groupService.assignRole(g1, gone)
        groupService.assignRole(g2, banner)

        when:
        Map spec = service.composeSpec(d)

        then: 'one play per repo, base role first, missing roles excluded'
        spec.plays*.repoName == ['a', 'b']
        spec.plays[0].roles == ['svenager_base', 'kiosk']
        spec.plays[0].commit == 'commit-a'
        spec.plays[1].roles == ['banner']
        spec.plays[1].commit == 'commit-b'
        base.baseRole && !gone.baseRole
    }

    void "composeSpec merges variables with device overriding groups"() {
        given:
        Device d = device()
        AnsibleRepository r = repo('a')
        DeviceGroup g1 = new DeviceGroup(name: 'alpha').save(failOnError: true)
        DeviceGroup g2 = new DeviceGroup(name: 'beta').save(failOnError: true)
        groupService.addDevice(g1, d)
        groupService.addDevice(g2, d)
        groupService.assignRole(g1, role(r, 'kiosk'))
        groupService.replaceVariables(g1, null, [
                [name: 'text', value: 'from-alpha', secret: false],
                [name: 'alpha_only', value: 1, secret: false]])
        groupService.replaceVariables(g2, null, [
                [name: 'text', value: 'from-beta', secret: false],
                [name: 'psk', value: 'hunter2', secret: true]])
        groupService.replaceVariables(null, d, [[name: 'text', value: 'from-device', secret: false]])

        when:
        Map spec = service.composeSpec(d)

        then: 'device wins, later group (by name) beats earlier, secrets are decrypted'
        spec.extraVars == [text: 'from-device', alpha_only: 1, psk: 'hunter2', svenager_hostname: 'kiosk']
        spec.secretVars == ['psk']
    }

    void "the UI-managed device name is injected and cannot be spoofed by vars"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        groupService.replaceVariables(g, null, [[name: 'svenager_hostname', value: 'spoofed', secret: false]])

        expect:
        service.composeSpec(d).extraVars.svenager_hostname == 'kiosk'
    }

    void "delivery redacts secrets in the stored payload but ships them on the wire"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        groupService.replaceVariables(g, null, [[name: 'psk', value: 'hunter2', secret: true]])
        service.enqueueApply(d, 'admin')

        when:
        Map wire = service.deliverNext(d)

        then:
        wire.payload.extraVars.psk == 'hunter2'
        Job.first().status == JobStatus.DELIVERED
        !Job.first().payloadJson.contains('hunter2')
        Job.first().payloadJson.contains('***')
    }

    void "a device without roles gets its job cancelled instead of delivered"() {
        given:
        Device d = device()
        service.enqueueApply(d, 'admin')

        expect:
        service.deliverNext(d) == null
        Job.first().status == JobStatus.CANCELLED
    }

    void "enqueue is deduplicated while a job is pending"() {
        given:
        Device d = device()

        expect:
        service.enqueueApply(d, 'admin').id == service.enqueueApply(d, 'admin').id
        Job.count() == 1
    }

    void "config drift is applied automatically at the next check-in"() {
        given: 'a device with an assigned role'
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))

        when: 'the device checks in without anyone pressing Apply'
        Map first = service.deliverNext(d)

        then: 'an auto job is queued and delivered'
        first != null
        Job.first().triggeredBy == 'auto (config changed)'
        Job.first().specHash != null

        when: 'the job succeeds and the device checks in again'
        service.handleEvent(d, first.id as String, [event: 'finished', exitCode: 0])

        then: 'nothing new — the device is converged'
        service.deliverNext(d) == null

        when: 'an operator changes a variable'
        groupService.replaceVariables(g, null, [[name: 'text', value: 'changed', secret: false]])

        then: 'the next check-in delivers a fresh apply'
        service.deliverNext(d) != null
        Job.count() == 2
    }

    void "a transiently failing apply recovers on a later check-in without an operator"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        Map first = service.deliverNext(d)
        service.handleEvent(d, first.id as String, [event: 'finished', exitCode: 2])

        when: 'the next check-in delivers an automatic retry that succeeds'
        Map retry = service.deliverNext(d)
        service.handleEvent(d, retry.id as String, [event: 'finished', exitCode: 0])

        then: 'the device is converged again'
        retry != null
        Job.findByUuid(retry.id as String).triggeredBy == 'auto (retry 2 of 3)'
        service.deliverNext(d) == null
        Job.countByDevice(d) == 2
    }

    void "a permanently failing apply stops after the retry bound"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))

        when: 'every attempt fails'
        3.times {
            Map wire = service.deliverNext(d)
            assert wire != null
            service.handleEvent(d, wire.id as String, [event: 'finished', exitCode: 2])
        }

        then: 'no fourth attempt, never two open jobs at once'
        service.deliverNext(d) == null
        Job.countByDevice(d) == 3
        Job.findAllByDevice(d).every { it.status == JobStatus.FAILED }

        when: 'the spec changes'
        groupService.replaceVariables(null, d, [[name: 'text', value: 'v2', secret: false]])

        then: 'convergence kicks in again with a fresh attempt counter'
        service.deliverNext(d) != null
        Job.findByDeviceAndStatus(d, JobStatus.DELIVERED).attempt == 1
    }

    void "a cancelled apply is not auto-retried"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        Map first = service.deliverNext(d)
        service.handleEvent(d, first.id as String, [event: 'started'])
        service.cancelJob(Job.findByUuid(first.id as String), 'admin')

        expect:
        service.deliverNext(d) == null
    }

    void "OPEN_TUNNEL jobs deliver the session payload with the remaining time budget"() {
        given: 'a device without roles, so no auto apply interferes'
        Device d = device()
        RemoteSession session = new RemoteSession(device: d, requestedBy: 'admin',
                expiresAt: new Date(System.currentTimeMillis() + 600_000)).save(failOnError: true)
        new Job(device: d, type: JobType.OPEN_TUNNEL,
                payloadJson: JsonOutput.toJson([sessionId: session.uuid, vncPort: 5900]))
                .save(failOnError: true)

        when:
        Map wire = service.deliverNext(d)

        then:
        wire.type == 'OPEN_TUNNEL'
        wire.payload.sessionId == session.uuid
        wire.payload.vncPort == 5900
        wire.payload.maxSeconds > 0
        wire.payload.maxSeconds <= 600
    }

    void "OPEN_TUNNEL jobs are cancelled when their session already ended"() {
        given:
        Device d = device()
        RemoteSession session = new RemoteSession(device: d, status: RemoteSessionStatus.CLOSED,
                expiresAt: new Date(System.currentTimeMillis() + 600_000)).save(failOnError: true)
        new Job(device: d, type: JobType.OPEN_TUNNEL,
                payloadJson: JsonOutput.toJson([sessionId: session.uuid, vncPort: 5900]))
                .save(failOnError: true)

        expect:
        service.deliverNext(d) == null
        Job.first().status == JobStatus.CANCELLED
    }

    void "tunnel jobs jump the queue ahead of pending applies"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        service.enqueueApply(d, 'admin')
        RemoteSession session = new RemoteSession(device: d,
                expiresAt: new Date(System.currentTimeMillis() + 600_000)).save(failOnError: true)
        new Job(device: d, type: JobType.OPEN_TUNNEL,
                payloadJson: JsonOutput.toJson([sessionId: session.uuid, vncPort: 5900]))
                .save(failOnError: true)

        expect: 'the newer tunnel job is delivered before the older apply'
        service.deliverNext(d).type == 'OPEN_TUNNEL'
        service.deliverNext(d).type == 'APPLY_CONFIG'
    }

    void "preview jobs ship the composed spec in check mode without a spec hash"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        service.enqueuePreview(d, 'admin')

        when: 'the preview was enqueued first, so it is delivered first'
        Map wire = service.deliverNext(d)

        then:
        wire.type == 'CHECK_CONFIG'
        wire.payload.plays*.repoName == ['a']
        Job.findByType(JobType.CHECK_CONFIG).specHash == null

        and: 'a preview never counts as the delivered apply for drift detection'
        service.handleEvent(d, wire.id as String, [event: 'finished', exitCode: 0])
        service.deliverNext(d)?.type == 'APPLY_CONFIG'
    }

    void "agent updates deliver the stored payload and are deduplicated"() {
        given:
        Device d = device()
        Job job = service.enqueueAgentUpdate(d, '1.2.3', 'admin')

        expect: 'enqueue is idempotent while pending'
        service.enqueueAgentUpdate(d, '9.9.9', 'admin').id == job.id
        Job.count() == 1

        when:
        Map wire = service.deliverNext(d)

        then:
        wire.type == 'AGENT_UPDATE'
        wire.payload.version == '1.2.3'
    }

    void "the global sweep times out stuck jobs without any check-in"() {
        given: 'a delivered job from a device that went silent, and a fresh one'
        Device d = device()
        new Job(device: d, status: JobStatus.DELIVERED,
                deliveredAt: new Date(System.currentTimeMillis() - 3 * 3600_000L)).save(failOnError: true)
        new Job(device: d, status: JobStatus.DELIVERED, deliveredAt: new Date()).save(failOnError: true)

        expect: 'only the stale job transitions to TIMED_OUT'
        service.timeoutStuckJobsGlobally() == 1
        Job.countByStatus(JobStatus.TIMED_OUT) == 1
        Job.countByStatus(JobStatus.DELIVERED) == 1
    }

    void "a failure alert fires only when the retries are exhausted"() {
        given:
        List<Long> alerted = []
        service.notificationService = Stub(NotificationService) {
            jobFailed(_) >> { Job j -> alerted << j.id }
        }
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))

        when: 'all three attempts fail'
        3.times {
            Map wire = service.deliverNext(d)
            service.handleEvent(d, wire.id as String, [event: 'finished', exitCode: 2])
        }

        then: 'exactly one alert, for the final attempt'
        alerted.size() == 1
        Job.get(alerted.first()).attempt == 3
    }

    void "a scheduled apply is not delivered before its time"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        Job job = service.enqueueApply(d, 'admin', null, new Date(System.currentTimeMillis() + 3600_000))

        expect: 'a check-in one hour early delivers nothing'
        service.deliverNext(d) == null
        job.status == JobStatus.PENDING

        when: 'the scheduled time has passed'
        job.runAfter = new Date(System.currentTimeMillis() - 1000)
        job.save(failOnError: true, flush: true)

        then: 'the next check-in delivers it'
        service.deliverNext(d)?.id == job.uuid

        and: 're-applying reschedules the single open job instead of stacking'
        Job.countByDevice(d) == 1
    }

    void "cancel finishes a pending job without agent involvement"() {
        given:
        Device d = device()
        Job job = service.enqueueApply(d, 'admin')

        when:
        service.cancelJob(job, 'admin')

        then:
        job.status == JobStatus.CANCELLED
        job.finishedAt != null
        service.deliverNext(d) == null

        and: 'late agent events for it are rejected'
        !service.handleEvent(d, job.id, [event: 'finished', exitCode: 0])
        job.status == JobStatus.CANCELLED
    }

    void "cancel rejects already-finished jobs"() {
        given:
        Device d = device()
        Job job = new Job(device: d, status: JobStatus.SUCCEEDED).save(failOnError: true)

        when:
        service.cancelJob(job, 'admin')

        then:
        thrown(IllegalStateException)
    }

    void "rerun queues a fresh apply for terminal apply jobs only"() {
        given:
        Device d = device()
        Job failed = new Job(device: d, status: JobStatus.FAILED).save(failOnError: true)

        when:
        Job created = service.rerun(failed, 'admin')

        then:
        created.id != failed.id
        created.status == JobStatus.PENDING
        created.triggeredBy == 'admin'

        when: 'an active job cannot be re-run'
        service.rerun(created, 'admin')

        then:
        thrown(IllegalStateException)
    }

    void "group apply fans out as one batch and retry re-runs only failures"() {
        given:
        Device d1 = device()
        Device d2 = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d1)
        groupService.addDevice(g, d2)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))

        when:
        JobBatch batch = service.enqueueBatchForGroup(g, 'admin')

        then:
        Job.countByBatch(batch) == 2
        batch.deviceGroup.id == g.id

        when: 'one device fails, the other succeeds'
        Map w1 = service.deliverNext(d1)
        Map w2 = service.deliverNext(d2)
        service.handleEvent(d1, w1.id as String, [event: 'finished', exitCode: 2])
        service.handleEvent(d2, w2.id as String, [event: 'finished', exitCode: 0])
        JobBatch retry = service.retryFailed(batch, 'admin')

        then: 'only the failed device is re-enqueued'
        Job.countByBatch(retry) == 1
        Job.findByBatch(retry).device.id == d1.id
        Job.findByBatch(retry).status == JobStatus.PENDING
    }

    void "a failed canary stops the rollout and leaves other devices untouched"() {
        given: 'two devices, the recently-seen one becomes the canary'
        Device canary = device()
        canary.lastContactAt = new Date()
        Device other = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, canary)
        groupService.addDevice(g, other)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))

        when:
        JobBatch batch = service.enqueueBatchForGroup(g, 'admin', true)

        then: 'only the canary device got a job'
        batch.stage == 'CANARY'
        Job.countByBatch(batch) == 1
        Job.findByBatch(batch).device.id == canary.id

        when: 'the canary fails and someone tries to continue anyway'
        Map wire = service.deliverNext(canary)
        service.handleEvent(canary, wire.id as String, [event: 'finished', exitCode: 2])
        service.continueRollout(batch, 'admin')

        then:
        thrown(IllegalStateException)
        Job.countByBatch(batch) == 1
        Job.countByDevice(other) == 0
    }

    void "a successful canary continues the rollout to the rest of the group"() {
        given:
        Device canary = device()
        canary.lastContactAt = new Date()
        Device other = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, canary)
        groupService.addDevice(g, other)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        JobBatch batch = service.enqueueBatchForGroup(g, 'admin', true)

        when: 'continuing before the canary finished is rejected'
        service.continueRollout(batch, 'admin')

        then:
        thrown(IllegalStateException)

        when: 'the canary succeeds'
        Map wire = service.deliverNext(canary)
        service.handleEvent(canary, wire.id as String, [event: 'finished', exitCode: 0])
        service.continueRollout(batch, 'admin')

        then: 'the rest of the group joins the same batch'
        batch.stage == 'FULL'
        Job.countByBatch(batch) == 2
        Job.countByDevice(other) == 1
    }

    void "a pending apply is adopted by a new batch instead of duplicated"() {
        given:
        Device d = device()
        DeviceGroup g = new DeviceGroup(name: 'g').save(failOnError: true)
        groupService.addDevice(g, d)
        groupService.assignRole(g, role(repo('a'), 'kiosk'))
        Job existing = service.enqueueApply(d, 'admin')

        when:
        JobBatch batch = service.enqueueBatchForGroup(g, 'admin')

        then:
        Job.countByDevice(d) == 1
        existing.batch.id == batch.id
    }

    void "events drive the job lifecycle idempotently"() {
        given:
        Device d = device()
        Job job = new Job(device: d, payloadJson: JsonOutput.toJson([plays: []]), status: JobStatus.DELIVERED)
                .save(failOnError: true)

        expect:
        service.handleEvent(d, job.id, [event: 'started'])
        job.status == JobStatus.RUNNING

        and: 'duplicate log sequence numbers are ignored'
        service.handleEvent(d, job.id, [event: 'log', seq: 0, chunk: 'hello '])
        service.handleEvent(d, job.id, [event: 'log', seq: 0, chunk: 'DUPLICATE'])
        service.handleEvent(d, job.id, [event: 'log', seq: 1, chunk: 'world'])
        service.logOf(job) == 'hello world'

        and:
        service.handleEvent(d, job.id, [event: 'finished', exitCode: 0])
        job.status == JobStatus.SUCCEEDED
        d.lastJobAt != null

        and: 'events after completion are rejected'
        !service.handleEvent(d, job.id, [event: 'finished', exitCode: 1])
        job.status == JobStatus.SUCCEEDED
    }

    void "the first log chunk may arrive without a seq field"() {
        given: 'agents with JSON omitempty drop seq 0 entirely'
        Device d = device()
        Job job = new Job(device: d, status: JobStatus.DELIVERED).save(failOnError: true)

        expect:
        service.handleEvent(d, job.id, [event: 'log', chunk: 'first'])
        service.handleEvent(d, job.id, [event: 'log', seq: 1, chunk: ' second'])
        service.logOf(job) == 'first second'
    }

    void "events from the wrong device are rejected"() {
        given:
        Device owner = device()
        Device attacker = device()
        Job job = new Job(device: owner, status: JobStatus.DELIVERED).save(failOnError: true)

        expect:
        !service.handleEvent(attacker, job.id, [event: 'started'])
        job.status == JobStatus.DELIVERED
    }
}
