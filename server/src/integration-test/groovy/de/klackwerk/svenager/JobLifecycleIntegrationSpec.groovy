package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

/**
 * The full M3 loop over HTTP: repo sync → group with roles and variables →
 * apply → check-in delivers the composed job → bundle download → status and
 * log events → job history.
 */
@Integration
class JobLifecycleIntegrationSpec extends Specification {

    @Shared
    EnrollmentService enrollmentService
    @Shared
    RepoSyncService repoSyncService
    @Shared
    GroupService groupService

    @TempDir
    File upstream

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    private void gitInUpstream(String... args) {
        Process p = new ProcessBuilder((['git', '-C', upstream.absolutePath] + (args as List)) as List<String>)
                .redirectErrorStream(true).start()
        String out = p.inputStream.text
        assert p.waitFor() == 0, "git ${args} failed: ${out}"
    }

    void "a job flows from apply to success with logs and a downloadable bundle"() {
        given: 'an upstream repo with a base role and an assignable role'
        gitInUpstream('init', '-b', 'main')
        gitInUpstream('config', 'user.email', 's@e.org')
        gitInUpstream('config', 'user.name', 'Spec')
        new File(upstream, 'svenager.yml').text = 'roles:\n  base:\n    base: true\n    user_assignable: false\n'
        ['base', 'banner'].each {
            File meta = new File(upstream, "roles/${it}/meta/main.yml")
            meta.parentFile.mkdirs()
            meta.text = "galaxy_info:\n  description: ${it}\n"
        }
        gitInUpstream('add', '.')
        gitInUpstream('commit', '-m', 'init')

        and: 'server-side setup: repo synced, group with role + secret variable, enrolled device'
        Long repoId = null
        Long groupId = null
        String enrollmentToken = null
        AnsibleRepository.withNewTransaction {
            repoId = new AnsibleRepository(name: "job-it-${UUID.randomUUID()}",
                    gitUrl: "file://${upstream.absolutePath}", branch: 'main')
                    .save(failOnError: true, flush: true).id
        }
        repoSyncService.sync(repoId)
        AnsibleRepository.withNewTransaction {
            DeviceGroup group = new DeviceGroup(name: "job-it-${UUID.randomUUID()}").save(failOnError: true)
            groupId = group.id
            DiscoveredRole banner = DiscoveredRole.findByRepositoryAndName(AnsibleRepository.get(repoId), 'banner')
            groupService.assignRole(group, banner)
            groupService.replaceVariables(group, null, [
                    [name: 'banner_text', value: 'hello', secret: false],
                    [name: 'psk', value: 'hunter2', secret: true]])
            enrollmentToken = enrollmentService.createToken('job-it', 1, null, 'spec').token
        }
        def enroll = client.request('POST', '/api/v1/enroll', [enrollmentToken: enrollmentToken, hostname: 'job-device'])
        Map auth = [Authorization: "Bearer ${enroll.body.deviceToken}".toString()]
        Device.withNewTransaction {
            groupService.addDevice(DeviceGroup.get(groupId), Device.findByUuid(enroll.body.deviceId as String))
            true
        }

        when: 'an admin triggers apply'
        client.login('admin', 'admin')
        def apply = client.request('POST', "/api/v1/devices/${enroll.body.deviceId}/apply", [:], client.csrfHeader())

        then:
        apply.status == 201
        apply.body.status == 'PENDING'

        when: 'the device checks in'
        def checkin = client.request('POST', '/api/v1/agent/checkin', [agentVersion: 'it'], auth)

        then: 'it receives the composed job with pinned commit and decrypted secret'
        def job = checkin.body.job
        job != null
        job.payload.plays.size() == 1
        job.payload.plays[0].roles == ['base', 'banner']
        job.payload.plays[0].commit ==~ /[0-9a-f]{40}/
        job.payload.extraVars == [banner_text: 'hello', psk: 'hunter2', svenager_hostname: 'job-device']

        and: 'the stored payload redacts the secret'
        def jobView = client.request('GET', "/api/v1/jobs/${job.id}")
        jobView.body.payload.extraVars.psk == '***'
        jobView.body.status == 'DELIVERED'

        when: 'the device downloads the bundle'
        HttpResponse<byte[]> bundle = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${client.baseUrl}/api/v1/agent/jobs/${job.id}/bundles/${job.payload.plays[0].repoId}"))
                        .header('Authorization', auth.Authorization).build(),
                HttpResponse.BodyHandlers.ofByteArray())

        then: 'it is a gzip tarball containing the role files'
        bundle.statusCode() == 200
        new String(new GZIPInputStream(new ByteArrayInputStream(bundle.body())).readAllBytes(), 'ISO-8859-1')
                .contains('roles/banner/meta/main.yml')

        when: 'the device reports lifecycle events'
        client.request('POST', "/api/v1/agent/jobs/${job.id}/events", [event: 'started'], auth)
        client.request('POST', "/api/v1/agent/jobs/${job.id}/events", [event: 'log', seq: 0, chunk: 'PLAY [all]\n'], auth)
        client.request('POST', "/api/v1/agent/jobs/${job.id}/events", [event: 'finished', exitCode: 0], auth)
        def done = client.request('GET', "/api/v1/jobs/${job.id}")

        then:
        done.body.status == 'SUCCEEDED'
        done.body.log == 'PLAY [all]\n'
        done.body.attempt == 1
        done.body.maxAttempts == 3
        done.body.retriesExhausted == false

        and: 'the device now has a last-job timestamp'
        client.request('GET', "/api/v1/devices/${enroll.body.deviceId}").body.lastJobAt != null

        and: 'another device cannot touch this job'
        String otherToken = null
        EnrollmentToken.withNewTransaction {
            otherToken = enrollmentService.createToken('job-it-2', 1, null, 'spec').token
        }
        def otherEnroll = client.request('POST', '/api/v1/enroll', [enrollmentToken: otherToken, hostname: 'other'])
        client.request('POST', "/api/v1/agent/jobs/${job.id}/events", [event: 'started'],
                [Authorization: "Bearer ${otherEnroll.body.deviceToken}".toString()]).status == 409
    }
}
