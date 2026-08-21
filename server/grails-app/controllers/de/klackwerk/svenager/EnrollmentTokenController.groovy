package de.klackwerk.svenager

import grails.converters.JSON
import grails.core.GrailsApplication
import org.springframework.security.core.context.SecurityContextHolder

class EnrollmentTokenController {

    static allowedMethods = [index: 'GET', save: 'POST', delete: 'DELETE']

    EnrollmentService enrollmentService
    GrailsApplication grailsApplication
    AuditService auditService

    def index() {
        render(enrollmentService.listTokens().collect { summarize(it) } as JSON)
    }

    def save() {
        def body = request.JSON
        String label = body?.label?.toString()?.trim()
        if (!label) {
            response.status = 422
            render([error: 'label is required'] as JSON)
            return
        }
        Integer maxUses = body?.maxUses != null ? (body.maxUses as Integer) : 1
        if (maxUses < 1) {
            response.status = 422
            render([error: 'maxUses must be at least 1'] as JSON)
            return
        }
        Date expiresAt = null
        if (body?.expiresInHours != null) {
            int hours = body.expiresInHours as Integer
            if (hours > 0) {
                expiresAt = new Date(System.currentTimeMillis() + hours * 3600_000L)
            }
        }
        List<DeviceGroup> targetGroups = (body?.targetGroupIds ?: [])
                .collect { DeviceGroup.findByUuid(it as String) }.findAll()
        Map result = enrollmentService.createToken(label, maxUses, expiresAt,
                SecurityContextHolder.context?.authentication?.name, targetGroups)
        // Only metadata — the raw token value never reaches the audit log.
        auditService.record('token-created', 'enrollmentToken', (result.entity as EnrollmentToken).uuid,
                "created enrollment token '${label}' for ${maxUses} device${maxUses == 1 ? '' : 's'}")
        response.status = 201
        Map payload = summarize(result.entity as EnrollmentToken)
        // The raw token is shown exactly once; only its hash is stored.
        payload.token = result.token
        payload.installCommand = AgentInstallController.installCommand(
                AgentInstallController.instanceUrl(grailsApplication, request), result.token as String)
        render(payload as JSON)
    }

    def delete(String id) {
        String label = EnrollmentToken.findByUuid(id)?.label
        enrollmentService.revoke(id)
        auditService.record('token-revoked', 'enrollmentToken', id,
                "revoked enrollment token '${label ?: id}'")
        response.status = 204
        render('')
    }

    private static Map summarize(EnrollmentToken token) {
        [
                id       : token.uuid,
                label    : token.label,
                maxUses  : token.maxUses,
                usedCount: token.usedCount,
                expiresAt: token.expiresAt?.toInstant()?.toString(),
                revoked  : token.revoked,
                usable   : token.usable,
                createdBy: token.createdBy,
                createdAt: token.dateCreated?.toInstant()?.toString(),
                targetGroups: (token.targetGroups ?: []).collect { [id: it.uuid, name: it.name] }
                        .sort { it.name },
        ]
    }
}
