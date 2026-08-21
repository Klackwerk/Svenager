package de.klackwerk.svenager

import groovy.transform.CompileStatic

@CompileStatic
enum JobType {
    APPLY_CONFIG,
    /** Same spec as APPLY_CONFIG, run with ansible-playbook --check. */
    CHECK_CONFIG,
    /** Agent replaces itself with the signed binary from the server. */
    AGENT_UPDATE,
    PING,
    OPEN_TUNNEL
}
