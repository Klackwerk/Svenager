package de.klackwerk.svenager

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class AuditServiceSpec extends Specification implements ServiceUnitTest<AuditService>, DataTest {

    void setupSpec() {
        mockDomains(AuditLogEntry)
    }

    void "records fall back to the system actor and truncate long summaries"() {
        when:
        service.recordAs(null, 'login', 'user', 42, 'x' * 2000)

        then:
        AuditLogEntry.count() == 1
        with(AuditLogEntry.first()) {
            actor == 'system'
            action == 'login'
            entityType == 'user'
            entityId == '42'
            summary.length() == 1000
        }
    }

    void "variable summaries carry names and counts but never values"() {
        given:
        List<Map> entries = [
                [name: 'psk', value: 'hunter2', secret: true],
                [name: 'kiosk_url', value: 'https://x', secret: false],
        ]

        expect:
        AuditService.variablesSummary(entries) == 'replaced 2 variables (1 secret): kiosk_url, psk'
        !AuditService.variablesSummary(entries).contains('hunter2')
    }
}
