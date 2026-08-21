package de.klackwerk.svenager.security

import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.util.StringUtils

import java.util.function.Supplier

/**
 * The CSRF handler recommended by the Spring Security docs for single-page
 * applications using CookieCsrfTokenRepository: BREACH-protect tokens
 * rendered into responses, accept the plain cookie value from the
 * X-XSRF-TOKEN header, and eagerly materialize the cookie on every request.
 */
@CompileStatic
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler()
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler()

    @Override
    void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken)
        // Opt out of deferred token loading so the XSRF-TOKEN cookie is
        // written even on requests that never render the token.
        csrfToken.get()
    }

    @Override
    String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.headerName)
        (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken)
    }
}
