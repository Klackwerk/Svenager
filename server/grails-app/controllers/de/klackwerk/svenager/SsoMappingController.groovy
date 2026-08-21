package de.klackwerk.svenager

import grails.converters.JSON

/**
 * Admin CRUD for the dynamic IdP-group mappings. Changes take effect at
 * the affected users' next SSO sign-in.
 */
class SsoMappingController {

    static allowedMethods = [index: 'GET', save: 'POST', delete: 'DELETE']

    AuditService auditService

    def index() {
        render(SsoGroupMapping.list(sort: 'idpGroup').collect { summarize(it) } as JSON)
    }

    def save() {
        def body = request.JSON
        String idpGroup = body?.idpGroup?.toString()?.trim()
        if (!idpGroup) {
            respondError(422, 'idpGroup is required')
            return
        }
        UserRole role = null
        if (body?.role) {
            role = UserRole.values().find { it.name() == body.role.toString().toUpperCase() }
            if (role == null) {
                respondError(422, 'role must be ADMIN, OPERATOR or VIEWER')
                return
            }
        }
        DeviceGroup deviceGroup = null
        if (body?.deviceGroupId) {
            deviceGroup = DeviceGroup.findByUuid(body.deviceGroupId as String)
            if (deviceGroup == null) {
                respondError(404, 'device group not found')
                return
            }
        }
        if (role == null && deviceGroup == null) {
            respondError(422, 'a mapping needs a role, a device group or both')
            return
        }
        SsoGroupMapping mapping = null
        SsoGroupMapping.withTransaction {
            mapping = new SsoGroupMapping(idpGroup: idpGroup, role: role, deviceGroup: deviceGroup)
                    .save(failOnError: true, flush: true)
        }
        auditService.record('sso-mapping-created', 'ssoMapping', mapping.uuid, describe(mapping))
        response.status = 201
        render(summarize(mapping) as JSON)
    }

    def delete(String id) {
        SsoGroupMapping mapping = SsoGroupMapping.findByUuid(id)
        if (mapping == null) {
            respondError(404, 'mapping not found')
            return
        }
        auditService.record('sso-mapping-deleted', 'ssoMapping', id, "removed ${describe(mapping)}")
        SsoGroupMapping.withTransaction { mapping.delete(flush: true) }
        response.status = 204
        render('')
    }

    private static String describe(SsoGroupMapping mapping) {
        List<String> effects = []
        if (mapping.role) {
            effects << "role ${mapping.role.name()}".toString()
        }
        if (mapping.deviceGroup) {
            effects << "device group '${mapping.deviceGroup.name}'".toString()
        }
        "IdP group '${mapping.idpGroup}' → ${effects.join(' + ')}"
    }

    private static Map summarize(SsoGroupMapping mapping) {
        [
                id             : mapping.uuid,
                idpGroup       : mapping.idpGroup,
                role           : mapping.role?.name(),
                deviceGroupId  : mapping.deviceGroup?.uuid,
                deviceGroupName: mapping.deviceGroup?.name,
                createdAt      : mapping.dateCreated?.toInstant()?.toString(),
        ]
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
