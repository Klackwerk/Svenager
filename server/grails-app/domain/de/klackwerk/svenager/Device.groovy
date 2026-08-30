package de.klackwerk.svenager

class Device {

    String uuid = UUID.randomUUID().toString()
    String hostname
    String agentVersion
    String tokenHash
    String factsJson
    DeviceStatus status = DeviceStatus.ACTIVE
    Date lastContactAt
    /** Address reported by the agent (its interface towards the server). */
    String ip
    /** Address the server saw the last check-in from (NAT/proxy hop). */
    String lastIp
    Date lastJobAt
    /** Set while an offline alert is standing; cleared by the recovery. */
    Date offlineAlertedAt
    Date dateCreated

    static constraints = {
        uuid unique: true
        hostname blank: false, maxSize: 253
        agentVersion nullable: true, maxSize: 100
        tokenHash unique: true
        factsJson nullable: true
        lastContactAt nullable: true
        ip nullable: true, maxSize: 64
        lastIp nullable: true, maxSize: 64
        lastJobAt nullable: true
        offlineAlertedAt nullable: true
    }

    static mapping = {
        factsJson type: 'text'
        status enumType: 'string'
    }
}
