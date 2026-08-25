package de.klackwerk.svenager.tunnel

import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification
import spock.lang.Unroll

class TunnelHandshakeInterceptorSpec extends Specification {

    private static ServletServerHttpRequest request(Map<String, String> headers) {
        MockHttpServletRequest servlet = new MockHttpServletRequest('GET', '/api/v1/ui/vnc/abc')
        servlet.scheme = 'http'
        servlet.serverName = 'svenager.kulkos.net'
        servlet.serverPort = 8080
        headers.each { k, v -> servlet.addHeader(k, v) }
        new ServletServerHttpRequest(servlet)
    }

    @Unroll
    void "origin #origin with #headers and externalUrl '#externalUrl' -> #allowed"() {
        expect:
        TunnelHandshakeInterceptor.originAllowed(request(headers + (origin ? [Origin: origin] : [:])), externalUrl) == allowed

        where:
        origin                        | headers                                                                        | externalUrl                   || allowed
        null                          | [Host: 'svenager.kulkos.net']                                                  | ''                            || true
        'https://svenager.kulkos.net' | [Host: 'svenager.kulkos.net', 'X-Forwarded-Proto': 'https']                    | ''                            || true
        'https://svenager.kulkos.net' | [Host: 'svenager.kulkos.net']                                                  | 'https://svenager.kulkos.net/' || true
        'https://svenager.kulkos.net' | [Host: 'svenager.kulkos.net']                                                  | ''                            || false
        'https://svenager.kulkos.net' | [Host: '10.42.0.5:8080', 'X-Forwarded-Host': 'svenager.kulkos.net', 'X-Forwarded-Proto': 'https'] | '' || true
        'https://evil.example'        | [Host: 'svenager.kulkos.net', 'X-Forwarded-Proto': 'https']                    | 'https://svenager.kulkos.net' || false
        'http://localhost:5173'       | [Host: 'localhost:5173']                                                       | ''                            || true
        'http://localhost:5173'       | [Host: 'localhost:8080']                                                       | ''                            || false
        'HTTPS://Svenager.Kulkos.net:443' | [Host: 'svenager.kulkos.net', 'X-Forwarded-Proto': 'https']                | ''                            || true
    }
}
