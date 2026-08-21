package de.klackwerk.svenager.security

import groovy.transform.CompileStatic

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Generation and hashing of API tokens (enrollment tokens, device tokens).
 *
 * Tokens are 256-bit random values, so a fast SHA-256 digest is a safe
 * at-rest representation (unlike user passwords, which are low-entropy and
 * use bcrypt via Spring Security's PasswordEncoder).
 */
@CompileStatic
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom()

    private Tokens() {}

    /** Generates a token like {@code svet_Kj3...}; the prefix aids secret scanning. */
    static String generate(String prefix) {
        byte[] bytes = new byte[32]
        RANDOM.nextBytes(bytes)
        prefix + '_' + Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }

    static String hash(String token) {
        MessageDigest.getInstance('SHA-256')
                .digest(token.getBytes('UTF-8'))
                .encodeHex()
                .toString()
    }
}
