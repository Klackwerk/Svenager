package de.klackwerk.svenager

class AnsibleRepository {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String name
    String gitUrl
    String branch = 'main'
    /** Public half of the generated deploy key, shown in the UI. */
    String deployKeyPublic
    /** Private half, AES-GCM encrypted at rest (see CryptoService). */
    String deployKeyPrivateEnc
    RepoSyncStatus syncStatus = RepoSyncStatus.NEVER
    String syncError
    String lastCommit
    Date lastSyncedAt
    Date dateCreated

    static constraints = {
        uuid unique: true
        name unique: true, blank: false, maxSize: 190
        gitUrl blank: false, maxSize: 1000
        branch blank: false, maxSize: 190
        deployKeyPublic nullable: true
        deployKeyPrivateEnc nullable: true
        syncError nullable: true
        lastCommit nullable: true, maxSize: 64
        lastSyncedAt nullable: true
    }

    static mapping = {
        deployKeyPublic type: 'text'
        deployKeyPrivateEnc type: 'text'
        syncError type: 'text'
        syncStatus enumType: 'string'
    }
}
