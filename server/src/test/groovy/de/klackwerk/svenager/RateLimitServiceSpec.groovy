package de.klackwerk.svenager

import spock.lang.Specification

class RateLimitServiceSpec extends Specification {

    RateLimitService service = new RateLimitService()

    void "a caller may burst up to the per-minute limit, then is throttled"() {
        expect:
        (1..5).every { service.allow('enroll:10.0.0.1', 5) }
        !service.allow('enroll:10.0.0.1', 5)
    }

    void "keys are independent buckets"() {
        given:
        3.times { service.allow('enroll:10.0.0.1', 3) }

        expect:
        !service.allow('enroll:10.0.0.1', 3)
        service.allow('enroll:10.0.0.2', 3)
        service.allow('install:10.0.0.1', 3)
    }

    void "a non-positive limit disables throttling"() {
        expect:
        (1..100).every { service.allow('kiosk:10.0.0.1', 0) }
    }
}
