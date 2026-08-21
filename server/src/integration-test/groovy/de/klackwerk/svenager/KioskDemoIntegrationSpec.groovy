package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Shared
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Integration
class KioskDemoIntegrationSpec extends Specification {

    @Shared
    EnrollmentService enrollmentService

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    void "the kiosk demo page is public and shows the device's freshness"() {
        given: 'an enrolled device that just checked in'
        String token = null
        EnrollmentToken.withNewTransaction {
            token = enrollmentService.createToken('kiosk-it', 1, null, 'spec').token
        }
        def enroll = client.request('POST', '/api/v1/enroll',
                [enrollmentToken: token, hostname: 'kiosk-demo-device'])
        client.request('POST', '/api/v1/agent/checkin', [:],
                [Authorization: "Bearer ${enroll.body.deviceToken}".toString()])

        when: 'the page is fetched without any session'
        HttpResponse<String> page = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${client.baseUrl}/kiosk-demo/${enroll.body.deviceId}")).build(),
                HttpResponse.BodyHandlers.ofString())

        then:
        page.statusCode() == 200
        page.body().contains('SVENAGER')
        page.body().contains('kiosk-demo-device')
        page.body().contains('online')
        page.body().contains('just now')

        and: 'unknown devices get a 404'
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${client.baseUrl}/kiosk-demo/does-not-exist")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode() == 404

        when: 'the local kiosk page (a file:// origin) polls the status JSON'
        HttpResponse<String> status = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${client.baseUrl}/kiosk-demo/${enroll.body.deviceId}/status")).build(),
                HttpResponse.BodyHandlers.ofString())

        then: 'it is public, cross-origin readable and carries the flags data'
        status.statusCode() == 200
        status.headers().firstValue('Access-Control-Allow-Origin').orElse('') == '*'
        status.body().contains('"hostname":"kiosk-demo-device"')
        status.body().contains('"online":true')
        status.body().contains('"lastContact":"just now"')
    }
}
