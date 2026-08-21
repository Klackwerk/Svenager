package de.klackwerk.svenager

import groovy.transform.CompileStatic

@CompileStatic
enum EnrollmentRequestStatus {
    /** Waiting for an admin decision; the device keeps polling. */
    PENDING,
    /** Approved — the device receives its token at its next poll. */
    APPROVED,
    /** Denied — the device is told to stop asking. */
    DENIED,
    /** Approved and the token was handed out; the device is enrolled. */
    COMPLETED
}
