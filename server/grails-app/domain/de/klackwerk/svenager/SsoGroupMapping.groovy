package de.klackwerk.svenager

/**
 * Maps one IdP group to a Svenager role and/or a device-group scope.
 * Applied at every SSO sign-in — the IdP stays the source of truth.
 */
class SsoGroupMapping {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String idpGroup
    /** Role granted to members; null = no role from this mapping. */
    UserRole role
    /** Device group the member may access; null = no scope grant. */
    DeviceGroup deviceGroup
    Date dateCreated

    static constraints = {
        uuid unique: true
        idpGroup blank: false, maxSize: 190
        role nullable: true
        deviceGroup nullable: true, validator: { DeviceGroup value, SsoGroupMapping mapping ->
            (value != null || mapping.role != null) ?: 'sso.mapping.effect.required'
        }
    }

    static mapping = {
        role enumType: 'string'
    }
}
