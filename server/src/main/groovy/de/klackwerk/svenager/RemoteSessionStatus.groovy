package de.klackwerk.svenager

import groovy.transform.CompileStatic

@CompileStatic
enum RemoteSessionStatus {
    /** Created; waiting for the agent to pick up the OPEN_TUNNEL job. */
    PENDING,
    /** Agent tunnel is connected; the viewer can attach. */
    AGENT_CONNECTED,
    /** Agent and viewer are connected — remote view is live. */
    ACTIVE,
    /** Terminal — see closeReason for why. */
    CLOSED

    boolean isOpen() {
        this != CLOSED
    }
}
