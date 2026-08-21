package de.klackwerk.svenager

/**
 * A configuration variable scoped to either a group or a single device
 * (exactly one of the two is set). Values are stored as JSON so types
 * survive the round trip into Ansible extra-vars. Secret values are
 * AES-GCM encrypted at rest.
 */
class ConfigVariable {

    DeviceGroup deviceGroup
    Device device
    String name
    String valueJson
    boolean secret = false
    Date dateCreated
    Date lastUpdated

    static constraints = {
        deviceGroup nullable: true, validator: { val, obj ->
            (val != null) != (obj.device != null) ? true : 'configVariable.scope.exactlyOne'
        }
        device nullable: true
        name blank: false, maxSize: 190, matches: /[a-zA-Z_][a-zA-Z0-9_]*/
        valueJson blank: false
    }

    static mapping = {
        valueJson type: 'text'
    }
}
