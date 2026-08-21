package de.klackwerk.svenager

class User {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String username
    String passwordHash
    UserRole role = UserRole.VIEWER
    boolean enabled = true
    /** LOCAL = password account; OIDC = managed by the identity provider. */
    String source = 'LOCAL'
    /** false = access limited to the groups in UserGroupScope. */
    boolean allGroups = true
    Date dateCreated
    Date lastUpdated

    static constraints = {
        uuid unique: true
        username unique: true, blank: false, maxSize: 190
        passwordHash blank: false
        source inList: ['LOCAL', 'OIDC']
    }

    static mapping = {
        // "user" is a reserved word in PostgreSQL
        table 'app_user'
        role enumType: 'string'
    }
}
