package de.klackwerk.svenager.security

import spock.lang.Specification

class TokensSpec extends Specification {

    void "generated tokens carry the prefix and are unique"() {
        when:
        def tokens = (1..100).collect { Tokens.generate('svet') }

        then:
        tokens.every { it.startsWith('svet_') }
        tokens.every { it.length() > 40 }
        tokens.toSet().size() == 100
    }

    void "hashing is deterministic and does not reveal the token"() {
        given:
        String token = Tokens.generate('svdt')

        expect:
        Tokens.hash(token) == Tokens.hash(token)
        Tokens.hash(token) != Tokens.hash(token + 'x')
        Tokens.hash(token).length() == 64
        !Tokens.hash(token).contains(token)
    }
}
