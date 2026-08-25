package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/**
 * The encryption key must reach CryptoService through the configured
 * setting — a missing binding only surfaces in production on first use.
 */
@Integration
class CryptoConfigIntegrationSpec extends Specification {

    GrailsApplication grailsApplication
    CryptoService cryptoService

    void "SVENAGER_ENCRYPTION_KEY is bound to svenager.security.encryptionKey"() {
        expect: 'the setting mirrors the environment (empty when unset)'
        grailsApplication.config.getProperty('svenager.security.encryptionKey', String, '') ==
                (System.getenv('SVENAGER_ENCRYPTION_KEY') ?: '')

        and: 'the key is loadable at boot (BootStrap calls this)'
        cryptoService.verifyKey()
        cryptoService.decrypt(cryptoService.encrypt('probe')) == 'probe'
    }
}
