package de.klackwerk.svenager

import spock.lang.Specification
import spock.lang.Unroll

class RepositoryUrlSpec extends Specification {

    @Unroll
    void "gitUrlError('#url') -> #expected"() {
        expect:
        RepositoryController.gitUrlError(url) == expected

        where:
        url                                                        || expected
        'https://git.kulturkosmos.de/software/ansible-svenager.git' || null
        'https://git.example.org:8443/group/repo.git'               || null
        'git@git.example.org:group/repo.git'                        || null
        'ssh://git@git.example.org:2222/group/repo.git'             || null
        'file:///srv/repo.git'                                      || null
        'https://git.kulturkosmos.de:software/ansible-svenager.git' ||
                "'https://git.kulturkosmos.de:software/ansible-svenager.git' has a non-numeric port — did you mean https://git.kulturkosmos.de/software/ansible-svenager.git?"
        'http://oauth2@gitlab.example.org:group/sub/repo.git'       ||
                "'http://oauth2@gitlab.example.org:group/sub/repo.git' has a non-numeric port — did you mean http://oauth2@gitlab.example.org/group/sub/repo.git?"
    }
}
