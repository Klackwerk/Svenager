package de.klackwerk.svenager

import grails.converters.JSON
import org.springframework.security.core.context.SecurityContextHolder

class JobBatchController {

    static allowedMethods = [show: 'GET', retry: 'POST', continueRollout: 'POST', forGroup: 'GET']

    JobService jobService
    AccessService accessService

    /** 404 for unknown AND out-of-scope batches — no existence leaks. */
    private JobBatch visibleBatch(String id) {
        JobBatch batch = JobBatch.findByUuid(id)
        (batch != null && accessService.canSeeBatch(batch)) ? batch : null
    }

    def show(String id) {
        JobBatch batch = visibleBatch(id)
        if (batch == null) {
            respondError(404, 'batch not found')
            return
        }
        render(summarize(batch) as JSON)
    }

    /** Re-enqueues the batch's unsuccessful applies as a new batch. */
    def retry(String id) {
        JobBatch batch = visibleBatch(id)
        if (batch == null) {
            respondError(404, 'batch not found')
            return
        }
        JobBatch created = null
        JobBatch.withTransaction {
            created = jobService.retryFailed(batch, SecurityContextHolder.context?.authentication?.name)
        }
        response.status = 201
        render(summarize(created) as JSON)
    }

    /** Applies a successful canary batch to the rest of its group. */
    def continueRollout(String id) {
        JobBatch batch = visibleBatch(id)
        if (batch == null) {
            respondError(404, 'batch not found')
            return
        }
        try {
            JobBatch.withTransaction {
                jobService.continueRollout(batch, SecurityContextHolder.context?.authentication?.name)
            }
            render(summarize(batch) as JSON)
        } catch (IllegalStateException e) {
            respondError(409, e.message)
        }
    }

    def forGroup(String id) {
        DeviceGroup group = DeviceGroup.findByUuid(id)
        if (group != null && !accessService.canSeeGroup(group)) {
            group = null
        }
        if (group == null) {
            respondError(404, 'group not found')
            return
        }
        List<JobBatch> batches = JobBatch.findAllByDeviceGroup(group,
                [sort: 'dateCreated', order: 'desc', max: 10])
        render(batches.collect { summarize(it) } as JSON)
    }

    static Map summarize(JobBatch batch) {
        List<Job> jobs = Job.findAllByBatch(batch, [sort: 'id', order: 'asc'])
        Map<String, Integer> counts = [:]
        jobs.each { counts[it.status.name()] = (counts[it.status.name()] ?: 0) + 1 }
        [
                id         : batch.uuid,
                groupId    : batch.deviceGroup?.uuid,
                groupName  : batch.deviceGroup?.name,
                triggeredBy: batch.triggeredBy,
                stage      : batch.stage,
                createdAt  : batch.dateCreated?.toInstant()?.toString(),
                total      : jobs.size(),
                counts     : counts,
                done       : jobs.every { it.status.terminal },
                jobs       : jobs.collect { JobController.summarize(it) },
        ]
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
