package de.klackwerk.svenager

import grails.converters.JSON
import groovy.json.JsonSlurper

class JobController {

    static allowedMethods = [index: 'GET', show: 'GET', cancel: 'POST', rerun: 'POST']

    JobService jobService
    AccessService accessService

    /** 404 for unknown AND out-of-scope jobs — no existence leaks. */
    private Job visibleJob(String id) {
        Job job = Job.findByUuid(id)
        (job != null && accessService.canSeeDevice(job.device)) ? job : null
    }

    /**
     * Job history with server-side filters so searches span all pages:
     * deviceId, status (comma-separated), type, groupId (membership at query
     * time), q (hostname or triggeredBy) and from/to (queued date range).
     */
    def index() {
        int max = Math.min(params.int('max') ?: 50, 200)
        int offset = Math.max(params.int('offset') ?: 0, 0)
        Device device = params.deviceId ? Device.findByUuid(params.deviceId as String) : null
        DeviceGroup group = params.groupId ? DeviceGroup.findByUuid(params.groupId as String) : null
        List<Device> groupDevices = group ? GroupMembership.findAllByDeviceGroup(group)*.device : null
        List<JobStatus> statuses = (params.status ?: '').tokenize(',').collect { String name ->
            JobStatus.values().find { it.name() == name }
        }.findAll()
        JobType type = JobType.values().find { it.name() == params.type }
        String q = params.q?.toString()?.trim()
        Date from = parseDate(params.from as String, false)
        Date to = parseDate(params.to as String, true)

        boolean noMatches = (params.deviceId && device == null) ||
                (group != null && !groupDevices) || (group == null && params.groupId)
        if (noMatches) {
            render([items: [], total: 0, offset: offset, max: max] as JSON)
            return
        }

        Set<Long> scopeIds = accessService.visibleDeviceIds()
        List<Device> scopeDevices = scopeIds ? Device.getAll(scopeIds as List<Long>) : []
        def jobs = Job.createCriteria().list(max: max, offset: offset) {
            if (scopeIds != null) {
                if (scopeDevices) {
                    'in'('device', scopeDevices)
                } else {
                    eq('id', -1L)
                }
            }
            if (device) {
                eq('device', device)
            }
            if (groupDevices != null) {
                'in'('device', groupDevices)
            }
            if (statuses) {
                'in'('status', statuses)
            }
            if (type) {
                eq('type', type)
            }
            if (from) {
                ge('dateCreated', from)
            }
            if (to) {
                le('dateCreated', to)
            }
            if (q) {
                createAlias('device', 'd')
                or {
                    ilike('d.hostname', "%${q}%")
                    ilike('triggeredBy', "%${q}%")
                }
            }
            order('dateCreated', 'desc')
        }
        render([items: jobs.collect { summarize(it) }, total: jobs.totalCount, offset: offset, max: max] as JSON)
    }

    /** Accepts yyyy-MM-dd (end-of-day for `to`) or a full ISO instant. */
    static Date parseDate(String value, boolean endOfDay) {
        if (!value) {
            return null
        }
        try {
            if (value.length() == 10) {
                Date day = Date.from(java.time.LocalDate.parse(value)
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
                return endOfDay ? new Date(day.time + 24L * 3600_000 - 1) : day
            }
            return Date.from(java.time.Instant.parse(value))
        } catch (ignored) {
            return null
        }
    }

    def show(String id) {
        Job job = visibleJob(id)
        if (job == null) {
            response.status = 404
            render([error: 'job not found'] as JSON)
            return
        }
        Map result = summarize(job)
        result.payload = job.payloadJson ? new JsonSlurper().parseText(job.payloadJson) : null
        result.log = jobService.logOf(job)
        render(result as JSON)
    }

    def cancel(String id) {
        Job job = visibleJob(id)
        if (job == null) {
            response.status = 404
            render([error: 'job not found'] as JSON)
            return
        }
        try {
            Job.withTransaction {
                jobService.cancelJob(job,
                        org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name)
            }
            render(summarize(job) as JSON)
        } catch (IllegalStateException e) {
            response.status = 409
            render([error: e.message] as JSON)
        }
    }

    def rerun(String id) {
        Job job = visibleJob(id)
        if (job == null) {
            response.status = 404
            render([error: 'job not found'] as JSON)
            return
        }
        try {
            Job created = null
            Job.withTransaction {
                created = jobService.rerun(job,
                        org.springframework.security.core.context.SecurityContextHolder.context?.authentication?.name)
            }
            response.status = 201
            render(summarize(created) as JSON)
        } catch (IllegalStateException e) {
            response.status = 409
            render([error: e.message] as JSON)
        }
    }

    static Map summarize(Job job) {
        [
                id         : job.uuid,
                deviceId   : job.device.uuid,
                hostname   : job.device.hostname,
                type       : job.type.name(),
                status     : job.status.name(),
                exitCode   : job.exitCode,
                error      : job.error,
                triggeredBy: job.triggeredBy,
                attempt    : job.attempt,
                maxAttempts: JobService.retryBound(),
                retriesExhausted: JobService.retriesExhausted(job),
                runAfter   : job.runAfter?.toInstant()?.toString(),
                queuedAt   : job.dateCreated?.toInstant()?.toString(),
                startedAt  : job.startedAt?.toInstant()?.toString(),
                finishedAt : job.finishedAt?.toInstant()?.toString(),
        ]
    }
}
