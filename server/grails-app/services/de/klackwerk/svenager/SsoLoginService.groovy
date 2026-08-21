package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

/**
 * Resolves an IdP identity to a Svenager account: role from the group
 * claim (config admin group, static config mapping, dynamic in-app
 * mappings), just-in-time provisioning, and device-group scope sync.
 */
@Transactional
class SsoLoginService {

    private static final List<UserRole> ROLE_PRIORITY =
            [UserRole.ADMIN, UserRole.OPERATOR, UserRole.VIEWER]

    GrailsApplication grailsApplication

    /**
     * Highest role granted by any of the user's IdP groups, from three
     * sources: svenager.sso.adminGroup, svenager.sso.roleMapping and the
     * dynamic SsoGroupMapping rows. Null (with no defaultRole) = reject.
     */
    UserRole resolveRole(List<String> idpGroups) {
        Set<UserRole> matched = [] as Set<UserRole>
        String adminGroup = grailsApplication.config.getProperty('svenager.sso.adminGroup', String, '')
        if (adminGroup && idpGroups.contains(adminGroup)) {
            matched << UserRole.ADMIN
        }
        Map staticMapping = grailsApplication.config.getProperty('svenager.sso.roleMapping', Map, [:])
        idpGroups.each { String group ->
            UserRole role = parseRole(staticMapping?[group]?.toString())
            if (role != null) {
                matched << role
            }
        }
        if (idpGroups) {
            SsoGroupMapping.findAllByIdpGroupInListAndRoleIsNotNull(idpGroups).each { matched << it.role }
        }
        UserRole best = ROLE_PRIORITY.find { it in matched }
        if (best != null) {
            return best
        }
        parseRole(grailsApplication.config.getProperty('svenager.sso.defaultRole', String, ''))
    }

    private static UserRole parseRole(String raw) {
        if (!raw?.trim()) {
            return null
        }
        UserRole.values().find { it.name() == raw.trim().toUpperCase() }
    }

    /** Device groups granted by the dynamic mappings for these IdP groups. */
    List<DeviceGroup> mappedDeviceGroups(List<String> idpGroups) {
        if (!idpGroups) {
            return []
        }
        SsoGroupMapping.findAllByIdpGroupInListAndDeviceGroupIsNotNull(idpGroups)
                *.deviceGroup.unique { it.id }
    }

    /**
     * Handles one successful IdP authentication: provisions or updates the
     * account and syncs role + device-group scope from the mappings.
     * Admins are always fleet-wide; a non-admin with mapped device groups
     * is scoped to exactly those, otherwise stays fleet-wide.
     */
    User login(String username, List<String> idpGroups) {
        if (!username?.trim()) {
            throw new SsoLoginException('the identity provider sent no usable username')
        }
        List<String> groups = idpGroups ?: []
        UserRole role = resolveRole(groups)
        if (role == null) {
            throw new SsoLoginException("no Svenager role is mapped for this account " +
                    "(IdP groups: ${groups ?: 'none'})")
        }
        User user = User.findByUsername(username.trim())
        if (user != null && user.source != 'OIDC') {
            throw new SsoLoginException("a local account named '${username}' already exists")
        }
        if (user != null && !user.enabled) {
            throw new SsoLoginException("the account '${username}' is disabled")
        }
        if (user == null) {
            // Unusable local password — SSO accounts sign in via the IdP only.
            user = new User(username: username.trim(), source: 'OIDC',
                    passwordHash: '{noop}sso-' + UUID.randomUUID())
        }
        user.role = role
        List<DeviceGroup> scoped = role == UserRole.ADMIN ? [] : mappedDeviceGroups(groups)
        user.allGroups = scoped.isEmpty()
        user.save(failOnError: true)
        UserGroupScope.findAllByUser(user)*.delete()
        scoped.each { new UserGroupScope(user: user, deviceGroup: it).save(failOnError: true) }
        user
    }
}
