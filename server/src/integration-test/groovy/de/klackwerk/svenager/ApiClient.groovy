package de.klackwerk.svenager

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Minimal HTTP helper for integration tests: JSON in/out, cookie jar for the
 * session + CSRF flow, bearer tokens for agent endpoints.
 */
class ApiClient {

    final String baseUrl
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build()

    ApiClient(int port) {
        baseUrl = "http://localhost:${port}"
    }

    Map<String, Object> request(String method, String path, Map body = null, Map<String, String> headers = [:]) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        headers.each { k, v -> builder.header(k, v) }
        if (body != null) {
            builder.header('Content-Type', 'application/json')
            builder.method(method, HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        Object json = null
        if (response.body()) {
            try {
                json = new JsonSlurper().parseText(response.body())
            } catch (ignored) {
                // non-JSON body
            }
        }
        [status: response.statusCode(), body: json]
    }

    String csrfToken() {
        CookieManager manager = http.cookieHandler().get() as CookieManager
        manager.cookieStore.cookies.find { it.name == 'XSRF-TOKEN' }?.value
    }

    /** Fetches the CSRF cookie, then logs in; returns the login response. */
    Map<String, Object> login(String username, String password) {
        request('GET', '/api/v1/auth/me')
        request('POST', '/api/v1/auth/login', [username: username, password: password],
                ['X-XSRF-TOKEN': csrfToken()])
    }

    Map<String, String> csrfHeader() {
        ['X-XSRF-TOKEN': csrfToken()]
    }

    /** WebSocket builder sharing this client's cookie jar (session auth). */
    java.net.http.WebSocket.Builder webSocketBuilder() {
        http.newWebSocketBuilder()
    }
}
