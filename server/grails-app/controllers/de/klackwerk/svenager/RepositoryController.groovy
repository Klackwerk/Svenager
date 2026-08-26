package de.klackwerk.svenager

import grails.converters.JSON
import groovy.json.JsonSlurper

class RepositoryController {

    static allowedMethods = [index: 'GET', save: 'POST', update: 'PUT', delete: 'DELETE', sync: 'POST', roles: 'GET']

    RepoSyncService repoSyncService
    CryptoService cryptoService
    AuditService auditService

    def index() {
        render(AnsibleRepository.list(sort: 'name').collect { summarize(it) } as JSON)
    }

    def save() {
        def body = request.JSON
        String name = body?.name?.toString()?.trim()
        String gitUrl = body?.gitUrl?.toString()?.trim()
        if (!name || !gitUrl) {
            respondError(422, 'name and gitUrl are required')
            return
        }
        if (AnsibleRepository.findByName(name)) {
            respondError(409, 'a repository with this name already exists')
            return
        }
        String urlError = gitUrlError(gitUrl)
        if (urlError) {
            respondError(422, urlError)
            return
        }
        AnsibleRepository repo = new AnsibleRepository(
                name: name,
                gitUrl: gitUrl,
                branch: body?.branch?.toString()?.trim() ?: 'main')
        String authError = applyAuth(repo, body)
        if (authError) {
            respondError(422, authError)
            return
        }
        AnsibleRepository.withTransaction { repo.save(failOnError: true, flush: true) }
        auditService.record('repo-created', 'repository', repo.uuid,
                "added repository '${repo.name}' (${repo.gitUrl} @ ${repo.branch}, auth ${repo.authType})")
        response.status = 201
        render(summarize(repo) as JSON)
    }

    /**
     * Edits name/URL/ref/credentials in place. Discovered roles and
     * assignments are kept; the next sync discards the stale checkout when
     * the URL changed and re-clones (built into RepoSyncService).
     */
    def update(String id) {
        AnsibleRepository repo = AnsibleRepository.findByUuid(id)
        if (!repo) {
            respondError(404, 'repository not found')
            return
        }
        def body = request.JSON
        String name = body?.name?.toString()?.trim()
        String gitUrl = body?.gitUrl?.toString()?.trim()
        String branch = body?.branch?.toString()?.trim()
        if (name && name != repo.name && AnsibleRepository.findByName(name)) {
            respondError(409, 'a repository with this name already exists')
            return
        }
        String urlError = gitUrl ? gitUrlError(gitUrl) : null
        if (urlError) {
            respondError(422, urlError)
            return
        }
        if (name) repo.name = name
        if (gitUrl) repo.gitUrl = gitUrl
        if (branch) repo.branch = branch
        String authError = applyAuth(repo, body)
        if (authError) {
            repo.discard()
            respondError(422, authError)
            return
        }
        AnsibleRepository.withTransaction { repo.save(failOnError: true, flush: true) }
        auditService.record('repo-updated', 'repository', repo.uuid,
                "updated repository '${repo.name}' (${repo.gitUrl} @ ${repo.branch}, auth ${repo.authType})")
        render(summarize(repo) as JSON)
    }

    def delete(String id) {
        AnsibleRepository repo = AnsibleRepository.findByUuid(id)
        if (!repo) {
            respondError(404, 'repository not found')
            return
        }
        auditService.record('repo-deleted', 'repository', repo.uuid,
                "removed repository '${repo.name}' with its roles and assignments")
        AnsibleRepository.withTransaction {
            List<DiscoveredRole> roles = DiscoveredRole.findAllByRepository(repo)
            roles.each { role -> GroupRoleAssignment.findAllByRole(role)*.delete() }
            roles*.delete()
            repo.delete()
        }
        response.status = 204
        render('')
    }

    def sync(String id) {
        AnsibleRepository repo = repoSyncService.sync(id)
        if (!repo) {
            respondError(404, 'repository not found')
            return
        }
        render(summarize(repo) as JSON)
    }

    def roles(String id) {
        AnsibleRepository repo = AnsibleRepository.findByUuid(id)
        if (!repo) {
            respondError(404, 'repository not found')
            return
        }
        render(DiscoveredRole.findAllByRepository(repo).sort { it.name }.collect { roleDetails(it) } as JSON)
    }

    static Map roleDetails(DiscoveredRole role) {
        [
                id            : role.uuid,
                name          : role.name,
                displayName   : role.displayName ?: role.name,
                description   : role.description,
                userAssignable: role.userAssignable,
                baseRole      : role.baseRole,
                missing       : role.missing,
                repositoryId  : role.repository.uuid,
                repository    : role.repository.name,
                argumentSpec  : role.argumentSpecJson ? new JsonSlurper().parseText(role.argumentSpecJson) : [:],
                defaults      : role.defaultsJson ? new JsonSlurper().parseText(role.defaultsJson) : [:],
        ]
    }

    /**
     * Catches the common SCP-style slip in an http(s) URL
     * (https://host:group/repo.git) before git fails on the "port".
     */
    static String gitUrlError(String gitUrl) {
        def m = gitUrl =~ /(?i)^(https?:\/\/)([^\/@]+@)?([^\/:]+):([^\/\d][^\/]*)(\/.*)?$/
        if (m.matches()) {
            String fixed = "${m.group(1)}${m.group(2) ?: ''}${m.group(3)}/${m.group(4)}${m.group(5) ?: ''}"
            return "'${gitUrl}' has a non-numeric port — did you mean ${fixed}?".toString()
        }
        null
    }

    /**
     * Applies the credential part of a create/update body. Returns an error
     * message or null. Secrets left out of an update keep their stored
     * value; switching the type drops the old credentials.
     *
     * Body fields: authType (NONE|SSH_KEY|HTTPS_TOKEN), sshPrivateKey,
     * generateDeployKey, authUsername, authSecret.
     */
    private String applyAuth(AnsibleRepository repo, def body) {
        RepoAuthType authType = repo.authType
        String requested = body?.authType?.toString()?.trim()
        if (requested) {
            authType = RepoAuthType.values().find { it.name() == requested }
            if (!authType) {
                return "unknown authType '${requested}'"
            }
        } else if (body?.generateDeployKey) {
            authType = RepoAuthType.SSH_KEY
        }
        if (authType != repo.authType) {
            repo.deployKeyPublic = null
            repo.deployKeyPrivateEnc = null
            repo.authUsername = null
            repo.authSecretEnc = null
            repo.authType = authType
        }
        boolean httpUrl = repo.gitUrl ==~ /(?i)https?:\/\/.*/
        switch (authType) {
            case RepoAuthType.SSH_KEY:
                if (httpUrl) {
                    return 'an SSH key needs an SSH git URL (git@host:group/repo.git or ssh://host/group/repo.git)'
                }
                String privateKey = body?.sshPrivateKey?.toString()?.trim()
                Map key = null
                if (privateKey) {
                    try {
                        key = repoSyncService.importDeployKey(privateKey)
                    } catch (IllegalArgumentException e) {
                        return e.message
                    }
                } else if (body?.generateDeployKey || !repo.deployKeyPrivateEnc) {
                    key = repoSyncService.generateDeployKey("svenager-${repo.name}".toString())
                }
                if (key) {
                    repo.deployKeyPublic = key.publicKey
                    repo.deployKeyPrivateEnc = key.privateKeyEncrypted
                }
                break
            case RepoAuthType.HTTPS_TOKEN:
                if (!httpUrl) {
                    return 'a username and token need an HTTP(S) git URL'
                }
                String username = body?.authUsername?.toString()?.trim()
                String secret = body?.authSecret?.toString()
                if (username) repo.authUsername = username
                if (secret) repo.authSecretEnc = cryptoService.encrypt(secret)
                if (!repo.authUsername || !repo.authSecretEnc) {
                    return 'authUsername and authSecret are required for HTTPS_TOKEN'
                }
                break
            default:
                break
        }
        null
    }

    private static Map summarize(AnsibleRepository repo) {
        [
                id             : repo.uuid,
                name           : repo.name,
                gitUrl         : repo.gitUrl,
                branch         : repo.branch,
                authType       : repo.authType.name(),
                authUsername   : repo.authUsername,
                hasCredentials : repo.deployKeyPrivateEnc != null || repo.authSecretEnc != null,
                deployKeyPublic: repo.deployKeyPublic,
                syncStatus     : repo.syncStatus.name(),
                syncError      : repo.syncError,
                lastCommit     : repo.lastCommit,
                lastSyncedAt   : repo.lastSyncedAt?.toInstant()?.toString(),
                roleCount      : DiscoveredRole.countByRepositoryAndMissing(repo, false),
        ]
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
