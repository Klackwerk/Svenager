package de.klackwerk.svenager

import groovy.transform.CompileStatic

@CompileStatic
enum JobStatus {
    PENDING,
    DELIVERED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED

    boolean isTerminal() {
        this in [SUCCEEDED, FAILED, TIMED_OUT, CANCELLED]
    }
}
