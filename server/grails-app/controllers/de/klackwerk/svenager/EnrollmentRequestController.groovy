package de.klackwerk.svenager

import grails.converters.JSON
import groovy.json.JsonSlurper
import org.springframework.security.core.context.SecurityContextHolder

/** Admin review of token-less enrollment requests. */
class EnrollmentRequestController {

    static allowedMethods = [index: 'GET', approve: 'POST', deny: 'POST']

    EnrollmentService enrollmentService
    AuditService auditService

    def index() {
        render(enrollmentService.listRequests().collect { summarize(it) } as JSON)
    }

    def approve(String id) {
        decide(id, true)
    }

    def deny(String id) {
        decide(id, false)
    }

    private void decide(String id, boolean approved) {
        try {
            enrollmentService.decideRequest(id, approved,
                    SecurityContextHolder.context?.authentication?.name)
            EnrollmentRequest decided = EnrollmentRequest.findByUuid(id)
            auditService.record(approved ? 'enrollment-approved' : 'enrollment-denied',
                    'enrollmentRequest', id,
                    "${approved ? 'approved' : 'denied'} enrollment of '${decided.hostname ?: decided.requestId}'")
            render(summarize(decided) as JSON)
        } catch (EnrollmentException e) {
            response.status = 409
            render([error: e.message] as JSON)
        }
    }

    private static Map summarize(EnrollmentRequest request) {
        [
                id        : request.uuid,
                requestId : request.requestId,
                hostname  : request.hostname,
                facts     : request.factsJson ? new JsonSlurper().parseText(request.factsJson) : [:],
                status    : request.status.name(),
                requestedAt: request.dateCreated?.toInstant()?.toString(),
                lastSeenAt: request.lastSeenAt?.toInstant()?.toString(),
                decidedBy : request.decidedBy,
                decidedAt : request.decidedAt?.toInstant()?.toString(),
                deviceId  : request.device?.uuid,
        ]
    }
}
