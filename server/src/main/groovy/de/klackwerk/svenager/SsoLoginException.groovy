package de.klackwerk.svenager

import groovy.transform.CompileStatic
import groovy.transform.InheritConstructors

/** An SSO sign-in that must be rejected (no role, conflict, disabled). */
@CompileStatic
@InheritConstructors
class SsoLoginException extends RuntimeException {
}
