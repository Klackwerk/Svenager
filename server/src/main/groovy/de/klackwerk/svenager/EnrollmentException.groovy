package de.klackwerk.svenager

import groovy.transform.CompileStatic
import groovy.transform.InheritConstructors

/** Thrown when enrollment is rejected (invalid, expired, exhausted or revoked token). */
@CompileStatic
@InheritConstructors
class EnrollmentException extends RuntimeException {
}
