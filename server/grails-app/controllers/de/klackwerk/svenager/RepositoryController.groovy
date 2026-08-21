package de.klackwerk.svenager

import grails.converters.JSON
import groovy.json.JsonSlurper

class RepositoryController {

    static allowedMethods = [index: 'GET', save: 'POST', update: 'PUT', delete: 'DELETE', sync: 'POST', roles: 'GET']

    RepoSyncService repoSyncService
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
        AnsibleRepository repo = new AnsibleRepository(
                name: name,
                gitUrl: gitUrl,
                branch: body?.branch?.toString()?.trim() ?: 'main')
        if (body?.generateDeployKey) {
            Map key = repoSyncService.generateDeployKey("svenager-${name}".toString())
            repo.deployKeyPublic = key.publicKey
            repo.deployKeyPrivateEnc = key.privateKeyEncrypted
        }
        AnsibleRepository.withTransaction { repo.save(failOnError: true, flush: true) }
        auditService.record('repo-created', 'repository', repo.uuid,
                "added repository '${repo.name}' (${repo.gitUrl} @ ${repo.branch})")
        response.status = 201
        render(summarize(repo) as JSON)
    }

    /**
     * Edits name/URL/ref in place. Discovered roles and assignments are
     * kept; the next sync discards the stale checkout when the URL changed
     * and re-clones (built into RepoSyncService).
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
        AnsibleRepository.withTransaction {
            if (name) repo.name = name
            if (gitUrl) repo.gitUrl = gitUrl
            if (branch) repo.branch = branch
            repo.save(failOnError: true, flush: true)
        }
        auditService.record('repo-updated', 'repository', repo.uuid,
                "updated repository '${repo.name}' (${repo.gitUrl} @ ${repo.branch})")
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

    private static Map summarize(AnsibleRepository repo) {
        [
                id             : repo.uuid,
                name           : repo.name,
                gitUrl         : repo.gitUrl,
                branch         : repo.branch,
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
