package de.klackwerk.svenager

import grails.converters.JSON
import org.springframework.security.core.context.SecurityContextHolder

/** Admin-only user management (enforced in SecurityConfig). */
class UserController {

    static allowedMethods = [index: 'GET', save: 'POST', update: 'PUT']

    UserService userService
    AuditService auditService

    def index() {
        render(userService.list().collect { summarize(it) } as JSON)
    }

    def save() {
        def body = request.JSON
        try {
            User user = userService.create(body?.username as String, body?.password as String,
                    parseRole(body?.role))
            auditService.record('user-created', 'user', user.uuid,
                    "created ${user.role.name()} user '${user.username}'")
            response.status = 201
            render(summarize(user) as JSON)
        } catch (IllegalArgumentException e) {
            response.status = 422
            render([error: e.message] as JSON)
        }
    }

    def update(String id) {
        def body = request.JSON
        try {
            User user = userService.update(id, parseRole(body?.role),
                    body?.containsKey('enabled') ? body.enabled as Boolean : null,
                    body?.password as String,
                    SecurityContextHolder.context?.authentication?.name)
            List<String> changes = []
            if (body?.role != null) {
                changes << "role set to ${user.role.name()}".toString()
            }
            if (body?.containsKey('enabled')) {
                changes << (user.enabled ? 'enabled' : 'disabled')
            }
            if (body?.password) {
                changes << 'password changed'
            }
            auditService.record('user-updated', 'user', user.uuid,
                    "user '${user.username}': ${changes.join(', ') ?: 'no changes'}")
            render(summarize(user) as JSON)
        } catch (IllegalArgumentException e) {
            response.status = e.message == 'user not found' ? 404 : 422
            render([error: e.message] as JSON)
        }
    }

    private static UserRole parseRole(Object raw) {
        if (raw == null) return null
        try {
            UserRole.valueOf(raw.toString().toUpperCase())
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("unknown role: ${raw}")
        }
    }

    private static Map summarize(User user) {
        [
                id       : user.uuid,
                username : user.username,
                role     : user.role.name(),
                enabled  : user.enabled,
                source   : user.source,
                allGroups: user.allGroups,
                scopes   : user.allGroups ? [] :
                        UserGroupScope.findAllByUser(user)*.deviceGroup*.name.sort(),
                createdAt: user.dateCreated?.toInstant()?.toString(),
        ]
    }
}
