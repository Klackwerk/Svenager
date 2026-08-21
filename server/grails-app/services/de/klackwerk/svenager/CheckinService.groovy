package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput

@Transactional
class CheckinService {

    static final int DEFAULT_POLL_INTERVAL_SECONDS = 60

    GrailsApplication grailsApplication
    JobService jobService
    GroupService groupService

    /** Records a device heartbeat and returns the check-in response payload. */
    Map checkin(Device device, String agentVersion, Map facts, String remoteIp = null) {
        device.lastContactAt = new Date()
        if (remoteIp) {
            device.lastIp = remoteIp
        }
        if (agentVersion) {
            device.agentVersion = agentVersion
        }
        if (facts) {
            device.factsJson = JsonOutput.toJson(facts)
            // The reported hostname only seeds an unnamed device — the name
            // is maintained in the UI and pushed to the device, not pulled.
            if (facts.hostname && !device.hostname) {
                device.hostname = facts.hostname as String
            }
        }
        device.save(failOnError: true)
        [pollIntervalSeconds: effectivePollIntervalSeconds(device), job: jobService.deliverNext(device)]
    }

    int getPollIntervalSeconds() {
        grailsApplication?.config?.getProperty('svenager.agent.pollIntervalSeconds', Integer, DEFAULT_POLL_INTERVAL_SECONDS) ?: DEFAULT_POLL_INTERVAL_SECONDS
    }

    /**
     * A group can override the global poll interval; a device in several
     * overriding groups uses the most frequent (smallest) one.
     */
    int effectivePollIntervalSeconds(Device device) {
        List<Integer> overrides = groupService.groupsOf(device)*.pollIntervalSeconds.findAll()
        overrides ? overrides.min() : pollIntervalSeconds
    }

    /** A device is online if it checked in within onlineFactor poll intervals. */
    boolean isOnline(Device device) {
        if (device.lastContactAt == null || device.status != DeviceStatus.ACTIVE) {
            return false
        }
        int factor = grailsApplication?.config?.getProperty('svenager.device.onlineFactor', Integer, 3) ?: 3
        device.lastContactAt.time >=
                System.currentTimeMillis() - (long) factor * effectivePollIntervalSeconds(device) * 1000
    }
}
