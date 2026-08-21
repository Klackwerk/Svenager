package de.klackwerk.svenager

/** One recorded administrative action; secret values are never stored. */
class AuditLogEntry {

    String actor
    String action
    String entityType
    String entityId
    String summary
    String ip
    Date dateCreated

    static constraints = {
        actor maxSize: 190
        action maxSize: 100
        entityType nullable: true, maxSize: 60
        entityId nullable: true, maxSize: 190
        summary nullable: true, maxSize: 1000
        ip nullable: true, maxSize: 64
    }

    static mapping = {
        dateCreated index: 'idx_audit_date_created'
    }
}
