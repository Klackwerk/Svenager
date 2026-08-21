package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Full sync pipeline against a real local git repository: clone, analyze,
 * upsert, re-sync with a removed role.
 */
@Integration
class RepoSyncIntegrationSpec extends Specification {

    @Shared
    RepoSyncService repoSyncService

    @Shared
    CryptoService cryptoService

    @TempDir
    File upstream

    private void gitInUpstream(String... args) {
        Process process = new ProcessBuilder((['git', '-C', upstream.absolutePath] + (args as List)) as List<String>)
                .redirectErrorStream(true).start()
        String output = process.inputStream.text
        assert process.waitFor() == 0, "git ${args} failed: ${output}"
    }

    private void writeRole(String name, String description) {
        File meta = new File(upstream, "roles/${name}/meta/main.yml")
        meta.parentFile.mkdirs()
        meta.text = "galaxy_info:\n  description: ${description}\n"
    }

    private Long createRepo() {
        Long id = null
        AnsibleRepository.withNewTransaction {
            id = new AnsibleRepository(name: "it-${UUID.randomUUID()}", gitUrl: "file://${upstream.absolutePath}",
                    branch: 'main').save(failOnError: true, flush: true).id
        }
        id
    }

    void "sync clones, analyzes and tracks removed roles"() {
        given: 'an upstream repository with two roles'
        gitInUpstream('init', '-b', 'main')
        gitInUpstream('config', 'user.email', 'spec@example.org')
        gitInUpstream('config', 'user.name', 'Spec')
        writeRole('base', 'Base role')
        writeRole('kiosk', 'Kiosk role')
        gitInUpstream('add', '.')
        gitInUpstream('commit', '-m', 'initial')
        Long repoId = createRepo()

        when:
        AnsibleRepository synced = repoSyncService.sync(repoId)

        then:
        synced.syncStatus == RepoSyncStatus.OK
        synced.lastCommit ==~ /[0-9a-f]{40}/
        AnsibleRepository.withNewTransaction {
            List<DiscoveredRole> roles = DiscoveredRole.findAllByRepository(AnsibleRepository.get(repoId))
            assert roles*.name.sort() == ['base', 'kiosk']
            assert roles.every { !it.missing }
            true
        }

        when: 'a role disappears upstream and we sync again'
        assert new File(upstream, 'roles/kiosk').deleteDir()
        gitInUpstream('add', '-A')
        gitInUpstream('commit', '-m', 'remove kiosk')
        repoSyncService.sync(repoId)

        then:
        AnsibleRepository.withNewTransaction {
            Map<String, Boolean> missingByName = DiscoveredRole
                    .findAllByRepository(AnsibleRepository.get(repoId))
                    .collectEntries { [it.name, it.missing] }
            assert missingByName == [base: false, kiosk: true]
            true
        }
    }

    void "a changed git URL discards the stale checkout instead of fetching from it"() {
        given: 'a first upstream, synced'
        gitInUpstream('init', '-b', 'main')
        gitInUpstream('config', 'user.email', 'spec@example.org')
        gitInUpstream('config', 'user.name', 'Spec')
        writeRole('base', 'Base role')
        gitInUpstream('add', '.')
        gitInUpstream('commit', '-m', 'initial')
        Long repoId = createRepo()
        assert repoSyncService.sync(repoId).syncStatus == RepoSyncStatus.OK

        and: 'the repository entity is switched to a different upstream'
        File otherUpstream = File.createTempDir()
        ['init', '-b', 'main'].with { new ProcessBuilder((['git', '-C', otherUpstream.absolutePath] + it) as List<String>).start().waitFor() }
        new ProcessBuilder('git', '-C', otherUpstream.absolutePath, 'config', 'user.email', 's@e.org').start().waitFor()
        new ProcessBuilder('git', '-C', otherUpstream.absolutePath, 'config', 'user.name', 'S').start().waitFor()
        File meta = new File(otherUpstream, 'roles/replacement/meta/main.yml')
        meta.parentFile.mkdirs()
        meta.text = 'galaxy_info:\n  description: Replacement role\n'
        new ProcessBuilder('git', '-C', otherUpstream.absolutePath, 'add', '.').start().waitFor()
        new ProcessBuilder('git', '-C', otherUpstream.absolutePath, 'commit', '-m', 'x').start().waitFor()
        AnsibleRepository.withNewTransaction {
            AnsibleRepository repo = AnsibleRepository.get(repoId)
            repo.gitUrl = "file://${otherUpstream.absolutePath}"
            repo.save(failOnError: true, flush: true)
        }

        when:
        AnsibleRepository synced = repoSyncService.sync(repoId)

        then:
        synced.syncStatus == RepoSyncStatus.OK
        AnsibleRepository.withNewTransaction {
            List<DiscoveredRole> roles = DiscoveredRole.findAllByRepository(AnsibleRepository.get(repoId))
            assert 'replacement' in roles*.name
            assert roles.find { it.name == 'base' }.missing
            true
        }

        cleanup:
        otherUpstream?.deleteDir()
    }

    void "a repository can pin a tag instead of a branch"() {
        given: 'an upstream where the tag points at the first of two commits'
        gitInUpstream('init', '-b', 'main')
        gitInUpstream('config', 'user.email', 'spec@example.org')
        gitInUpstream('config', 'user.name', 'Spec')
        writeRole('base', 'Base role')
        gitInUpstream('add', '.')
        gitInUpstream('commit', '-m', 'v1')
        gitInUpstream('tag', 'v1.0')
        writeRole('newer', 'Not in the tag')
        gitInUpstream('add', '.')
        gitInUpstream('commit', '-m', 'v2')

        and: 'a repository pinned to the tag'
        Long repoId = null
        AnsibleRepository.withNewTransaction {
            repoId = new AnsibleRepository(name: "tag-it-${UUID.randomUUID()}",
                    gitUrl: "file://${upstream.absolutePath}", branch: 'v1.0')
                    .save(failOnError: true, flush: true).id
        }

        when:
        AnsibleRepository synced = repoSyncService.sync(repoId)

        then: 'only the tagged state is visible'
        synced.syncStatus == RepoSyncStatus.OK
        synced.lastCommit ==~ /[0-9a-f]{40}/
        AnsibleRepository.withNewTransaction {
            assert DiscoveredRole.findAllByRepository(AnsibleRepository.get(repoId))*.name == ['base']
            true
        }

        and: 'the tag can be re-synced (update path, not just fresh clone)'
        repoSyncService.sync(repoId).syncStatus == RepoSyncStatus.OK
    }

    void "the repository source can be edited in place"() {
        given: 'a synced repository'
        gitInUpstream('init', '-b', 'main')
        gitInUpstream('config', 'user.email', 'spec@example.org')
        gitInUpstream('config', 'user.name', 'Spec')
        writeRole('base', 'Base role')
        gitInUpstream('add', '.')
        gitInUpstream('commit', '-m', 'initial')
        Long repoId = createRepo()
        assert repoSyncService.sync(repoId).syncStatus == RepoSyncStatus.OK

        when: 'an admin edits name and ref via the API'
        String repoUuid = null
        AnsibleRepository.withNewTransaction {
            repoUuid = AnsibleRepository.get(repoId).uuid
        }
        ApiClient client = new ApiClient(serverPort)
        client.login('admin', 'admin')
        def updated = client.request('PUT', "/api/v1/repositories/${repoUuid}",
                [name: 'Renamed config', branch: 'main'], client.csrfHeader())

        then: 'the change sticks and the repo still syncs'
        updated.status == 200
        updated.body.name == 'Renamed config'
        repoSyncService.sync(repoId).syncStatus == RepoSyncStatus.OK

        and: 'unauthenticated edits are rejected'
        new ApiClient(serverPort).request('PUT', "/api/v1/repositories/${repoUuid}", [name: 'x']).status in [401, 403]
    }

    void "sync failures are recorded, not thrown"() {
        given:
        Long repoId = null
        AnsibleRepository.withNewTransaction {
            repoId = new AnsibleRepository(name: "broken-${UUID.randomUUID()}",
                    gitUrl: 'file:///definitely/not/a/repo', branch: 'main')
                    .save(failOnError: true, flush: true).id
        }

        when:
        AnsibleRepository synced = repoSyncService.sync(repoId)

        then:
        synced.syncStatus == RepoSyncStatus.ERROR
        synced.syncError
    }

    void "crypto round trip works"() {
        expect:
        cryptoService.decrypt(cryptoService.encrypt('s3cret')) == 's3cret'
        cryptoService.encrypt('s3cret') != cryptoService.encrypt('s3cret')
    }
}
