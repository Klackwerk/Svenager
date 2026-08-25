package de.klackwerk.svenager

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

@Integration
class AgentInstallIntegrationSpec extends Specification {

    ApiClient client

    void setup() {
        client = new ApiClient(serverPort)
    }

    void "the install script is public, valid shell and knows the instance URL"() {
        when:
        Map response = client.request('GET', '/install.sh')

        then:
        response.status == 200

        and: 'it contains the moving parts of the one-step enrollment'
        String script = fetchText('/install.sh')
        script.startsWith('#!/bin/sh')
        script.contains("SERVER=\"${client.baseUrl}\"".toString())
        script.contains('--token')
        script.contains('/install/agent/linux-$ARCH')
        script.contains('svenager-agent enroll')
        script.contains('systemctl enable --now svenager-agent')

        and: 'it installs what the agent shells out to on a bare Debian'
        script.contains('command -v ansible-playbook')
        script.contains('apt-get install -y -qq --no-install-recommends git ansible-core')
    }

    void "behind a reverse proxy the script uses the forwarded origin"() {
        when:
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("${client.baseUrl}/install.sh"))
                        .header('X-Forwarded-Proto', 'https')
                        .header('X-Forwarded-Host', 'svenager.example.org')
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())

        then:
        response.body().contains('SERVER="https://svenager.example.org"')
    }

    void "agent binaries are served from the dist directory"() {
        given:
        File dist = new File('build/test-agent-dist')
        dist.mkdirs()
        new File(dist, 'svenager-agent-linux-arm64').bytes = 'FAKE-ELF'.bytes

        new File(dist, 'svenager-agent-linux-arm64.sig').text = 'BASE64SIG'

        expect: 'a hosted platform streams the binary'
        fetchText('/install/agent/linux-arm64') == 'FAKE-ELF'

        and: 'its detached signature is served for agent self-update'
        fetchText('/install/agent/linux-arm64.sig') == 'BASE64SIG'

        and: 'an unhosted platform explains what is missing'
        client.request('GET', '/install/agent/linux-amd64').status == 404

        and: 'arbitrary paths are rejected, not resolved'
        client.request('GET', '/install/agent/..%2fapplication.yml').status in [400, 404]
        client.request('GET', '/install/agent/linux-mips').status == 404
    }

    void "a created token ships a ready-to-paste install command"() {
        given:
        client.login('admin', 'admin')

        when:
        def created = client.request('POST', '/api/v1/enrollment-tokens',
                [label: 'one-liner', maxUses: 1, expiresInHours: 1], client.csrfHeader())

        then:
        created.status == 201
        created.body.installCommand ==
                "curl -fsSL ${client.baseUrl}/install.sh | sudo sh -s -- --token ${created.body.token}"
    }

    private String fetchText(String path) {
        new URL(client.baseUrl + path).text
    }
}
