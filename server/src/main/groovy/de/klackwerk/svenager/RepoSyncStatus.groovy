package de.klackwerk.svenager

import groovy.transform.CompileStatic

@CompileStatic
enum RepoSyncStatus {
    NEVER,
    SYNCING,
    OK,
    ERROR
}
