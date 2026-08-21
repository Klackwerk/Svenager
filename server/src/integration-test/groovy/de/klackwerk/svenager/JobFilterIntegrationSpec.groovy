package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/** Server-side job history filters: q, status, group and date range. */
@Integration
class JobFilterIntegrationSpec extends Specification {

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    void "job filters combine and pagination reflects the filtered total"() {
        given: 'two enrolled devices with one pending apply job each'
        client.login('admin', 'admin')
        def token = client.request('POST', '/api/v1/enrollment-tokens',
                [label: 'filter spec', maxUses: 2, expiresInHours: 1], client.csrfHeader())
        def alpha = client.request('POST', '/api/v1/enroll',
                [enrollmentToken: token.body.token, hostname: 'filter-alpha'])
        def beta = client.request('POST', '/api/v1/enroll',
                [enrollmentToken: token.body.token, hostname: 'filter-beta'])
        String alphaId = alpha.body.deviceId
        String betaId = beta.body.deviceId
        client.request('POST', "/api/v1/devices/${alphaId}/apply", null, client.csrfHeader())
        client.request('POST', "/api/v1/devices/${betaId}/apply", null, client.csrfHeader())
        def group = client.request('POST', '/api/v1/groups', [name: 'filter-spec-group'], client.csrfHeader())
        client.request('POST', "/api/v1/groups/${group.body.id}/devices",
                [deviceId: alphaId], client.csrfHeader())

        expect: 'q matches the hostname across history'
        with(client.request('GET', '/api/v1/jobs?q=filter-alpha').body) {
            total == 1
            items*.hostname == ['filter-alpha']
        }

        and: 'status combines with q'
        client.request('GET', '/api/v1/jobs?q=filter-&status=PENDING').body.total == 2
        client.request('GET', '/api/v1/jobs?q=filter-&status=SUCCEEDED,FAILED').body.total == 0

        and: 'the date range bounds the queued time'
        String tomorrow = java.time.LocalDate.now().plusDays(1).toString()
        String yesterday = java.time.LocalDate.now().minusDays(1).toString()
        client.request('GET', "/api/v1/jobs?q=filter-&from=${tomorrow}").body.total == 0
        client.request('GET', "/api/v1/jobs?q=filter-&from=${yesterday}&to=${tomorrow}").body.total == 2

        and: 'pagination reflects the filtered total'
        with(client.request('GET', '/api/v1/jobs?q=filter-&max=1').body) {
            total == 2
            items.size() == 1
        }

        and: 'a group scopes the history to its members'
        with(client.request('GET', "/api/v1/jobs?groupId=${group.body.id}").body) {
            total == 1
            items*.hostname == ['filter-alpha']
        }

        and: 'an unknown group matches nothing'
        client.request('GET', '/api/v1/jobs?groupId=999999').body.total == 0

        cleanup:
        if (group?.body?.id) {
            client.request('DELETE', "/api/v1/groups/${group.body.id}", null, client.csrfHeader())
        }
        [alphaId, betaId].findAll().each {
            client.request('DELETE', "/api/v1/devices/${it}", null, client.csrfHeader())
        }
    }
}
