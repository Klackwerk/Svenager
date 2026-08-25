package de.klackwerk.svenager

import groovy.transform.CompileStatic

/** How the server authenticates against a repository's git remote. */
@CompileStatic
enum RepoAuthType {
    /** Public repository, no credentials. */
    NONE,
    /** SSH private key — generated deploy key or an imported one. */
    SSH_KEY,
    /** HTTP(S) basic auth with a username and token/password. */
    HTTPS_TOKEN
}
