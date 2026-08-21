package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Clones/updates registered Ansible repositories and upserts the roles the
 * analyzer finds. Git runs as a subprocess; repository content is only ever
 * parsed, never executed.
 */
@Slf4j
class RepoSyncService {

    static final int GIT_TIMEOUT_SECONDS = 120

    GrailsApplication grailsApplication
    RepoAnalyzerService repoAnalyzerService
    CryptoService cryptoService
    NotificationService notificationService

    @Transactional
    AnsibleRepository sync(Serializable repoRef) {
        AnsibleRepository repo = repoRef instanceof Number ? AnsibleRepository.get(repoRef)
                : AnsibleRepository.findByUuid(repoRef as String)
        if (repo == null) {
            return null
        }
        RepoSyncStatus previousStatus = repo.syncStatus
        repo.syncStatus = RepoSyncStatus.SYNCING
        repo.syncError = null
        repo.save(flush: true)
        try {
            File workdir = workdirFor(repo)
            checkout(repo, workdir)
            repo.lastCommit = git(repo, workdir, 'rev-parse', 'HEAD').trim()
            Map analysis = repoAnalyzerService.analyze(workdir)
            upsertRoles(repo, analysis.roles as List<Map>)
            repo.syncStatus = RepoSyncStatus.OK
            repo.lastSyncedAt = new Date()
            List warnings = analysis.warnings as List
            repo.syncError = warnings ? warnings.join('\n') : null
        } catch (Exception e) {
            log.warn('Sync of repository {} failed', repo.name, e)
            repo.syncStatus = RepoSyncStatus.ERROR
            repo.syncError = e.message
            // Alert once per transition, not on every scheduled re-try.
            if (previousStatus != RepoSyncStatus.ERROR) {
                notificationService?.repoSyncFailed(repo, e.message)
            }
        }
        repo.save(failOnError: true, flush: true)
        repo
    }

    private File workdirFor(AnsibleRepository repo) {
        String base = grailsApplication.config.getProperty('svenager.repos.dir', String, 'build/repos')
        new File(base, "repo-${repo.id}")
    }

    private void checkout(AnsibleRepository repo, File workdir) {
        if (new File(workdir, '.git').directory && remoteUrl(repo, workdir) != repo.gitUrl) {
            // Stale checkout of a different remote (repo URL changed, or a
            // leftover workdir from a deleted repository with the same id).
            workdir.deleteDir()
        }
        if (new File(workdir, '.git').directory) {
            // FETCH_HEAD works for branches and tags alike — the configured
            // ref may be either.
            git(repo, workdir, 'fetch', '--depth', '1', 'origin', repo.branch)
            git(repo, workdir, 'checkout', '--force', '--detach', 'FETCH_HEAD')
        } else {
            workdir.deleteDir()
            workdir.parentFile?.mkdirs()
            git(repo, null, 'clone', '--depth', '1', '--branch', repo.branch, repo.gitUrl, workdir.absolutePath)
        }
    }

    /**
     * A tar.gz bundle of the repository at the given commit, for delivery to
     * agents (devices never need repository credentials). Falls back to a
     * fresh sync when the commit is not present locally.
     */
    byte[] archive(AnsibleRepository repo, String commit) {
        File workdir = workdirFor(repo)
        if (!hasCommit(repo, workdir, commit)) {
            checkout(repo, workdir)
        }
        if (!hasCommit(repo, workdir, commit)) {
            throw new IllegalStateException("commit ${commit} not found in ${repo.name} (${repo.branch})")
        }
        Path bundle = Files.createTempFile('svenager-bundle', '.tar.gz')
        try {
            git(repo, workdir, 'archive', '--format=tar.gz', '-o', bundle.toString(), commit)
            return Files.readAllBytes(bundle)
        } finally {
            Files.deleteIfExists(bundle)
        }
    }

    private boolean hasCommit(AnsibleRepository repo, File workdir, String commit) {
        if (!new File(workdir, '.git').directory) {
            return false
        }
        try {
            git(repo, workdir, 'cat-file', '-e', "${commit}^{commit}".toString())
            return true
        } catch (Exception ignored) {
            return false
        }
    }

    private String remoteUrl(AnsibleRepository repo, File workdir) {
        try {
            return git(repo, workdir, 'remote', 'get-url', 'origin').trim()
        } catch (Exception ignored) {
            return null
        }
    }

    /** Runs git, optionally with the repository's deploy key. */
    private String git(AnsibleRepository repo, File workdir, String... args) {
        List<String> command = ['git']
        if (workdir != null) {
            command += ['-C', workdir.absolutePath]
        }
        command += (args as List<String>)

        Path keyFile = null
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
            builder.redirectErrorStream(true)
            builder.environment().put('GIT_TERMINAL_PROMPT', '0')
            if (repo.deployKeyPrivateEnc) {
                keyFile = Files.createTempFile('svenager-deploy-key', '',
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rw-------')))
                Files.writeString(keyFile, cryptoService.decrypt(repo.deployKeyPrivateEnc) + '\n')
                builder.environment().put('GIT_SSH_COMMAND',
                        "ssh -i ${keyFile} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new".toString())
            }
            Process process = builder.start()
            String output = process.inputStream.getText('UTF-8')
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw new IllegalStateException("git ${args[0]} timed out after ${GIT_TIMEOUT_SECONDS}s")
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("git ${args[0]} failed: ${output.readLines().takeRight(5).join('; ')}")
            }
            return output
        } finally {
            if (keyFile != null) {
                Files.deleteIfExists(keyFile)
            }
        }
    }

    private void upsertRoles(AnsibleRepository repo, List<Map> found) {
        Set<String> foundNames = found*.name as Set<String>
        DiscoveredRole.findAllByRepository(repo).each { DiscoveredRole existing ->
            existing.missing = !(existing.name in foundNames)
            existing.save()
        }
        found.each { Map role ->
            DiscoveredRole entity = DiscoveredRole.findByRepositoryAndName(repo, role.name as String)
                    ?: new DiscoveredRole(repository: repo, name: role.name as String)
            entity.displayName = role.displayName
            entity.description = role.description
            entity.userAssignable = role.userAssignable as boolean
            entity.baseRole = role.baseRole as boolean
            entity.missing = false
            entity.argumentSpecJson = JsonOutput.toJson(role.argumentSpec ?: [:])
            entity.defaultsJson = JsonOutput.toJson(role.defaults ?: [:])
            entity.save(failOnError: true)
        }
    }

    /** Generates an ed25519 deploy key pair; stores the private half encrypted. */
    Map generateDeployKey(String comment) {
        Path dir = Files.createTempDirectory('svenager-keygen')
        Path keyPath = dir.resolve('key')
        try {
            Process process = new ProcessBuilder(
                    'ssh-keygen', '-t', 'ed25519', '-N', '', '-C', comment, '-f', keyPath.toString())
                    .redirectErrorStream(true).start()
            String output = process.inputStream.getText('UTF-8')
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("ssh-keygen failed: ${output.readLines().takeRight(3).join('; ')}")
            }
            [
                    publicKey        : Files.readString(dir.resolve('key.pub')).trim(),
                    privateKeyEncrypted: cryptoService.encrypt(Files.readString(keyPath)),
            ]
        } finally {
            dir.toFile().deleteDir()
        }
    }
}
