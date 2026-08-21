package de.klackwerk.svenager

import grails.converters.JSON

class DashboardController {

    static allowedMethods = [index: 'GET']

    DashboardService dashboardService
    AccessService accessService

    def index() {
        Map overview = dashboardService.overview()
        Set<Long> visible = accessService.visibleGroupIds()
        if (visible != null) {
            // Scoped users get aggregates over their groups only. Devices in
            // several visible groups count once per group — good enough for
            // an overview, exact numbers live on the Devices page.
            List groups = (overview.groups as List).findAll { (it.id as Long) in visible }
            int total = (groups.sum { it.deviceCount } ?: 0) as int
            int online = (groups.sum { it.onlineCount } ?: 0) as int
            overview.groups = groups
            overview.devices = [total: total, online: online, offline: total - online, ungrouped: 0]
            overview.jobs = [
                    succeeded: (groups.sum { it.jobs.succeeded } ?: 0) as int,
                    failed   : (groups.sum { it.jobs.failed } ?: 0) as int,
                    active   : (groups.sum { it.jobs.active } ?: 0) as int,
            ]
        }
        render(overview as JSON)
    }
}
