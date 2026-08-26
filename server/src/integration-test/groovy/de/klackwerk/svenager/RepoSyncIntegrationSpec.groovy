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

        and: 'the global search finds the role by name'
        ApiClient admin = new ApiClient(serverPort)
        admin.login('admin', 'admin')
        admin.request('GET', '/api/v1/search?q=kios').body.roles.any {
            it.name == 'kiosk' && it.repository == synced.name && !it.missing
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

    void "HTTPS credentials reach git through the helper's environment only"() {
        given: 'git configured exactly like RepoSyncService does it'
        ProcessBuilder builder = new ProcessBuilder('git', '-c', 'credential.helper=',
                '-c', "credential.helper=${RepoSyncService.CREDENTIAL_HELPER}".toString(), 'credential', 'fill')
        builder.redirectErrorStream(true)
        builder.environment().put('GIT_TERMINAL_PROMPT', '0')
        builder.environment().put(RepoSyncService.ENV_USERNAME, 'oauth2')
        builder.environment().put(RepoSyncService.ENV_PASSWORD, 'glpat-s3cret')

        when:
        Process process = builder.start()
        process.outputStream.withWriter { it << 'protocol=https\nhost=gitlab.example.com\n\n' }
        String output = process.inputStream.text
        int exit = process.waitFor()

        then:
        exit == 0
        output.readLines().contains('username=oauth2')
        output.readLines().contains('password=glpat-s3cret')
        !builder.command().join(' ').contains('glpat-s3cret')
    }

    void "an imported private key yields its public half"() {
        given: 'a key pair generated outside Svenager'
        File dir = File.createTempDir()
        Process keygen = new ProcessBuilder('ssh-keygen', '-t', 'ed25519', '-N', '', '-C', 'spec', '-f',
                "${dir}/id").redirectErrorStream(true).start()
        assert keygen.waitFor() == 0, keygen.inputStream.text
        String privateKey = new File(dir, 'id').text
        String publicKey = new File(dir, 'id.pub').text.trim()

        when:
        Map imported = repoSyncService.importDeployKey(privateKey)

        then: 'the public key matches (ssh-keygen -y omits the comment)'
        publicKey.startsWith(imported.publicKey as String)
        cryptoService.decrypt(imported.privateKeyEncrypted as String).trim() == privateKey.trim()

        when: 'garbage is imported'
        repoSyncService.importDeployKey('-----BEGIN OPENSSH PRIVATE KEY-----\nnope\n-----END OPENSSH PRIVATE KEY-----')

        then:
        thrown(IllegalArgumentException)

        cleanup:
        dir?.deleteDir()
    }

    void "repository credentials are managed through the API without leaking the secret"() {
        given:
        ApiClient client = new ApiClient(serverPort)
        client.login('admin', 'admin')
        String name = "auth-it-${UUID.randomUUID()}"

        when: 'a private HTTPS repository is registered'
        def created = client.request('POST', '/api/v1/repositories',
                [name: name, gitUrl: 'https://gitlab.example.com/ops/config.git', authType: 'HTTPS_TOKEN',
                 authUsername: 'oauth2', authSecret: 'glpat-s3cret'], client.csrfHeader())

        then: 'the summary tells the type and username but not the token'
        created.status == 201
        created.body.authType == 'HTTPS_TOKEN'
        created.body.authUsername == 'oauth2'
        created.body.hasCredentials == true
        !created.body.toString().contains('glpat-s3cret')
        AnsibleRepository.withNewTransaction {
            AnsibleRepository repo = AnsibleRepository.findByName(name)
            assert repo.authType == RepoAuthType.HTTPS_TOKEN
            assert cryptoService.decrypt(repo.authSecretEnc) == 'glpat-s3cret'
            true
        }

        when: 'the URL changes but the token is left out'
        String uuid = created.body.id
        def renamed = client.request('PUT', "/api/v1/repositories/${uuid}",
                [gitUrl: 'https://gitlab.example.com/ops/config-v2.git', authType: 'HTTPS_TOKEN', authUsername: 'oauth2'],
                client.csrfHeader())

        then: 'the stored token is kept'
        renamed.status == 200
        renamed.body.hasCredentials == true
        AnsibleRepository.withNewTransaction {
            assert cryptoService.decrypt(AnsibleRepository.findByName(name).authSecretEnc) == 'glpat-s3cret'
            true
        }

        when: 'the type is switched to an SSH URL with a generated deploy key'
        def ssh = client.request('PUT', "/api/v1/repositories/${uuid}",
                [gitUrl: 'git@gitlab.example.com:ops/config.git', authType: 'SSH_KEY', generateDeployKey: true],
                client.csrfHeader())

        then: 'the old token is gone and a public key is shown'
        ssh.status == 200
        ssh.body.authType == 'SSH_KEY'
        ssh.body.authUsername == null
        ssh.body.deployKeyPublic.toString().startsWith('ssh-ed25519 ')
        AnsibleRepository.withNewTransaction {
            AnsibleRepository repo = AnsibleRepository.findByName(name)
            assert repo.authSecretEnc == null
            assert repo.deployKeyPrivateEnc != null
            true
        }

        when: 'an SSH key is requested for an HTTPS URL'
        def mismatch = client.request('PUT', "/api/v1/repositories/${uuid}",
                [gitUrl: 'https://gitlab.example.com/ops/config.git', authType: 'SSH_KEY'], client.csrfHeader())

        then: 'the request is rejected and nothing changed'
        mismatch.status == 422
        AnsibleRepository.withNewTransaction {
            assert AnsibleRepository.findByName(name).gitUrl == 'git@gitlab.example.com:ops/config.git'
            true
        }

        when: 'authentication is switched off'
        def cleared = client.request('PUT', "/api/v1/repositories/${uuid}", [authType: 'NONE'], client.csrfHeader())

        then:
        cleared.status == 200
        cleared.body.authType == 'NONE'
        cleared.body.hasCredentials == false
        cleared.body.deployKeyPublic == null

        cleanup:
        client.request('DELETE', "/api/v1/repositories/${uuid}", null, client.csrfHeader())
    }

    void "crypto round trip works"() {
        expect:
        cryptoService.decrypt(cryptoService.encrypt('s3cret')) == 's3cret'
        cryptoService.encrypt('s3cret') != cryptoService.encrypt('s3cret')
    }
}
