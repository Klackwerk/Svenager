package de.klackwerk.svenager

import groovy.transform.CompileStatic

/** What a reverse-tunnel session carries. */
@CompileStatic
enum RemoteSessionKind {
    /** noVNC screen view/control. */
    VNC,
    /** Interactive shell (PTY) in the browser terminal. */
    SHELL
}
