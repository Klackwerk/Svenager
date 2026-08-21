package de.klackwerk.svenager

import grails.converters.JSON

/** All discovered roles across repositories, for the group role picker. */
class RoleController {

    static allowedMethods = [index: 'GET']

    def index() {
        List<DiscoveredRole> roles = DiscoveredRole.findAllByUserAssignableAndMissing(true, false)
        render(roles.sort { it.displayName ?: it.name }.collect { RepositoryController.roleDetails(it) } as JSON)
    }
}
