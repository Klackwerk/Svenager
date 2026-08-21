package de.klackwerk.svenager

import grails.converters.JSON

/** Admin-only, read-only audit trail with server-side filters. */
class AuditController {

    static allowedMethods = [index: 'GET']

    def index() {
        int max = Math.min(params.int('max') ?: 50, 200)
        int offset = Math.max(params.int('offset') ?: 0, 0)
        String q = params.q?.toString()?.trim()
        String actor = params.actor?.toString()?.trim()
        String entityType = params.entityType?.toString()?.trim()
        Date from = JobController.parseDate(params.from as String, false)
        Date to = JobController.parseDate(params.to as String, true)

        def entries = AuditLogEntry.createCriteria().list(max: max, offset: offset) {
            if (q) {
                or {
                    ilike('summary', "%${q}%")
                    ilike('action', "%${q}%")
                    ilike('actor', "%${q}%")
                    ilike('entityId', "%${q}%")
                }
            }
            if (actor) {
                ilike('actor', "%${actor}%")
            }
            if (entityType) {
                eq('entityType', entityType)
            }
            if (from) {
                ge('dateCreated', from)
            }
            if (to) {
                le('dateCreated', to)
            }
            order('dateCreated', 'desc')
            order('id', 'desc')
        }
        render([items: entries.collect { summarize(it) }, total: entries.totalCount,
                offset: offset, max: max] as JSON)
    }

    private static Map summarize(AuditLogEntry entry) {
        [
                id        : entry.id,
                actor     : entry.actor,
                action    : entry.action,
                entityType: entry.entityType,
                entityId  : entry.entityId,
                summary   : entry.summary,
                ip        : entry.ip,
                at        : entry.dateCreated?.toInstant()?.toString(),
        ]
    }
}
