package de.klackwerk.svenager

/** An Ansible role found by analyzing a registered repository. */
class DiscoveredRole {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    AnsibleRepository repository
    String name
    /** Friendly name from svenager.yml, falls back to the role name in the UI. */
    String displayName
    String description
    /** Parsed meta/argument_specs.yml main options, as JSON. */
    String argumentSpecJson
    /** Parsed defaults/main.yml, as JSON. */
    String defaultsJson
    boolean userAssignable = true
    /** Base roles run first on every device that uses roles from this repository. */
    boolean baseRole = false
    /** True when the role disappeared from the repository on a later sync. */
    boolean missing = false
    Date dateCreated
    Date lastUpdated

    static belongsTo = [repository: AnsibleRepository]

    static constraints = {
        uuid unique: true
        name blank: false, maxSize: 190, unique: 'repository'
        displayName nullable: true, maxSize: 190
        description nullable: true
        argumentSpecJson nullable: true
        defaultsJson nullable: true
    }

    static mapping = {
        description type: 'text'
        argumentSpecJson type: 'text'
        defaultsJson type: 'text'
    }
}
