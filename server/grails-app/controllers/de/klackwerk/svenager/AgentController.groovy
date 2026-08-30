package de.klackwerk.svenager

import de.klackwerk.svenager.security.DeviceTokenAuthFilter
import grails.converters.JSON
import grails.core.GrailsApplication

/** Endpoints called by enrolled device agents (authenticated by device token). */
class AgentController {

    static allowedMethods = [checkin: 'POST', events: 'POST', bundle: 'GET']

    CheckinService checkinService
    JobService jobService
    RepoSyncService repoSyncService
    GrailsApplication grailsApplication

    def checkin() {
        Device device = Device.get(request.getAttribute(DeviceTokenAuthFilter.DEVICE_ID_ATTRIBUTE) as Long)
        if (device == null) {
            response.status = 401
            render([error: 'unknown device'] as JSON)
            return
        }
        def body = request.JSON
        Map result = checkinService.checkin(
                device,
                body?.agentVersion as String,
                body?.facts instanceof Map ? body.facts as Map : null,
                remoteAddress())
        render(result as JSON)
    }

    /**
     * X-Forwarded-For is only honored behind a declared trusted proxy —
     * otherwise a device could record any address it likes.
     */
    private String remoteAddress() {
        boolean trustForwarded = grailsApplication.config.getProperty(
                'svenager.rateLimit.trustForwardedFor', Boolean, false)
        if (trustForwarded) {
            String forwarded = request.getHeader('X-Forwarded-For')?.tokenize(',')?.first()?.trim()
            if (forwarded) {
                return forwarded
            }
        }
        request.remoteAddr
    }

    def events(String id) {
        Device device = authenticatedDevice()
        if (device == null) {
            return
        }
        boolean accepted = jobService.handleEvent(device, id, request.JSON as Map)
        if (!accepted) {
            response.status = 409
            render([error: 'event rejected (unknown job, wrong device or job already finished)'] as JSON)
            return
        }
        render([ok: true] as JSON)
    }

    def bundle(String id, Long repoId) {
        Device device = authenticatedDevice()
        if (device == null) {
            return
        }
        Job job = Job.findByUuid(id) ?: (id?.isLong() ? Job.get(id.toLong()) : null)
        if (job == null || job.device.id != device.id || job.status.terminal) {
            response.status = 404
            render([error: 'no such job'] as JSON)
            return
        }
        Map play = (new groovy.json.JsonSlurper().parseText(job.payloadJson) as Map).plays
                .find { it.repoId as Long == repoId }
        AnsibleRepository repo = AnsibleRepository.get(repoId)
        if (play == null || repo == null) {
            response.status = 404
            render([error: 'repository is not part of this job'] as JSON)
            return
        }
        byte[] bundle = repoSyncService.archive(repo, play.commit as String)
        response.contentType = 'application/gzip'
        response.setHeader('Content-Disposition', "attachment; filename=repo-${repoId}.tar.gz")
        response.outputStream << bundle
        response.outputStream.flush()
    }

    private Device authenticatedDevice() {
        Device device = Device.get(request.getAttribute(de.klackwerk.svenager.security.DeviceTokenAuthFilter.DEVICE_ID_ATTRIBUTE) as Long)
        if (device == null) {
            response.status = 401
            render([error: 'unknown device'] as JSON)
        }
        device
    }
}
