package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import org.grails.web.util.WebUtils
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Writes the audit trail. Callers pass a short human-readable summary;
 * secret values must never reach it (see variablesSummary).
 */
@Transactional
class AuditService {

    GrailsApplication grailsApplication

    /** Records with the currently authenticated user as actor. */
    void record(String action, String entityType, Object entityId, String summary) {
        recordAs(SecurityContextHolder.context?.authentication?.name, action, entityType, entityId, summary)
    }

    void recordAs(String actor, String action, String entityType, Object entityId, String summary) {
        new AuditLogEntry(actor: actor ?: 'system', action: action, entityType: entityType,
                entityId: entityId?.toString(), summary: summary?.take(1000), ip: currentIp())
                .save(failOnError: true)
    }

    /** Variable-change summary: names and counts only, never values. */
    static String variablesSummary(List<Map> entries) {
        int secrets = entries.count { it.secret } as int
        List<String> names = entries.collect { it.name as String }.sort()
        "replaced ${entries.size()} variable${entries.size() == 1 ? '' : 's'} " +
                "(${secrets} secret): ${names.join(', ')}"
    }

    private String currentIp() {
        try {
            def request = WebUtils.retrieveGrailsWebRequest()?.currentRequest
            if (request == null) {
                return null
            }
            boolean trustForwarded = grailsApplication.config.getProperty(
                    'svenager.rateLimit.trustForwardedFor', Boolean, false)
            if (trustForwarded) {
                String forwarded = request.getHeader('X-Forwarded-For')
                if (forwarded) {
                    return forwarded.split(',')[0].trim()
                }
            }
            return request.remoteAddr
        } catch (ignored) {
            // Scheduler/agent contexts have no web request.
            return null
        }
    }
}
