package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Composes, queues and tracks jobs. An APPLY_CONFIG job tells the agent to
 * download pinned repository bundles and run the composed role list locally
 * with the merged variables.
 */
@Transactional
class JobService {

    static final int DEFAULT_TIMEOUT_SECONDS = 1800

    GrailsApplication grailsApplication
    CryptoService cryptoService
    GroupService groupService
    NotificationService notificationService

    // --- queueing ----------------------------------------------------------

    /**
     * Queues an APPLY_CONFIG job unless one is already pending; a pending
     * job is adopted into the new batch (and rescheduled) instead of
     * duplicated — one open apply per device, always.
     */
    Job enqueueApply(Device device, String triggeredBy, JobBatch batch = null, Date runAfter = null) {
        Job pending = Job.findByDeviceAndTypeAndStatus(device, JobType.APPLY_CONFIG, JobStatus.PENDING)
        if (pending) {
            boolean changed = false
            if (batch != null && pending.batch?.id != batch.id) {
                pending.batch = batch
                changed = true
            }
            if (pending.runAfter != runAfter) {
                pending.runAfter = runAfter
                changed = true
            }
            if (changed) {
                pending.save(failOnError: true)
            }
            return pending
        }
        new Job(device: device, type: JobType.APPLY_CONFIG, triggeredBy: triggeredBy, batch: batch,
                runAfter: runAfter)
                .save(failOnError: true)
    }

    /** Queues a check-mode preview job unless one is already pending. */
    Job enqueuePreview(Device device, String triggeredBy) {
        Job pending = Job.findByDeviceAndTypeAndStatus(device, JobType.CHECK_CONFIG, JobStatus.PENDING)
        if (pending) {
            return pending
        }
        new Job(device: device, type: JobType.CHECK_CONFIG, triggeredBy: triggeredBy).save(failOnError: true)
    }

    /** Queues a self-update job unless one is already pending. */
    Job enqueueAgentUpdate(Device device, String version, String triggeredBy) {
        Job pending = Job.findByDeviceAndTypeAndStatus(device, JobType.AGENT_UPDATE, JobStatus.PENDING)
        if (pending) {
            return pending
        }
        new Job(device: device, type: JobType.AGENT_UPDATE, triggeredBy: triggeredBy,
                payloadJson: JsonOutput.toJson([version: version ?: '']))
                .save(failOnError: true)
    }

    /**
     * Fans out one apply per group member, tracked as one batch. In canary
     * mode only the most recently seen device gets the job; the rest follow
     * via continueRollout once the canary succeeded.
     */
    JobBatch enqueueBatchForGroup(DeviceGroup group, String triggeredBy, boolean canary = false,
                                  Date runAfter = null) {
        List<Device> members = groupService.membersOf(group)
        if (canary && members.size() > 1) {
            JobBatch batch = new JobBatch(deviceGroup: group, triggeredBy: triggeredBy, stage: 'CANARY')
                    .save(failOnError: true)
            Device canaryDevice = members.max { it.lastContactAt?.time ?: 0L }
            enqueueApply(canaryDevice, triggeredBy, batch, runAfter)
            return batch
        }
        JobBatch batch = new JobBatch(deviceGroup: group, triggeredBy: triggeredBy).save(failOnError: true)
        members.each { enqueueApply(it, triggeredBy, batch, runAfter) }
        batch
    }

    /** Rolls a successful canary batch out to the rest of its group. */
    JobBatch continueRollout(JobBatch batch, String triggeredBy) {
        if (batch.stage != 'CANARY') {
            throw new IllegalStateException('this rollout has no canary stage to continue')
        }
        if (batch.deviceGroup == null) {
            throw new IllegalStateException('the group of this rollout no longer exists')
        }
        List<Job> jobs = Job.findAllByBatch(batch)
        if (!jobs || jobs.any { !it.status.terminal }) {
            throw new IllegalStateException('the canary has not finished yet')
        }
        if (jobs.any { it.status != JobStatus.SUCCEEDED }) {
            throw new IllegalStateException('the canary did not succeed — the rollout stays stopped')
        }
        Set<Long> alreadyApplied = jobs*.device*.id as Set<Long>
        batch.stage = 'FULL'
        batch.save(failOnError: true)
        groupService.membersOf(batch.deviceGroup)
                .findAll { !(it.id in alreadyApplied) }
                .each { enqueueApply(it, triggeredBy, batch) }
        batch
    }

    /** Re-runs a batch's unsuccessful applies as a new batch. */
    JobBatch retryFailed(JobBatch batch, String triggeredBy) {
        List<Device> failed = Job.findAllByBatchAndStatusInList(batch,
                [JobStatus.FAILED, JobStatus.TIMED_OUT, JobStatus.CANCELLED])*.device.unique { it.id }
        JobBatch retry = new JobBatch(deviceGroup: batch.deviceGroup, triggeredBy: triggeredBy)
                .save(failOnError: true)
        failed.each { enqueueApply(it, triggeredBy, retry) }
        retry
    }

    /**
     * Cancels a job that has not finished. PENDING jobs simply never deliver;
     * DELIVERED/RUNNING jobs keep running on the device, but their late
     * started/finished events are rejected (agent gets the 409 path).
     */
    Job cancelJob(Job job, String cancelledBy) {
        if (job.status.terminal) {
            throw new IllegalStateException('the job already finished')
        }
        job.status = JobStatus.CANCELLED
        job.error = "Cancelled by ${cancelledBy ?: 'an operator'}."
        job.finishedAt = new Date()
        job.save(failOnError: true)
    }

    /** Re-queues a finished APPLY_CONFIG job with a freshly composed spec. */
    Job rerun(Job job, String triggeredBy) {
        if (job.type != JobType.APPLY_CONFIG || !job.status.terminal) {
            throw new IllegalStateException('only finished apply jobs can be re-run')
        }
        enqueueApply(job.device, triggeredBy)
    }

    // --- delivery ----------------------------------------------------------

    /**
     * Returns the next job for a checking-in device as the wire payload
     * (with secrets decrypted), or null. Also times out stuck jobs.
     */
    Map deliverNext(Device device) {
        timeoutStuckJobs(device)
        ensureConverged(device)
        // Interactive tunnels jump the queue — an apply can wait, a person can't.
        Job job = nextPending(device, JobType.OPEN_TUNNEL) ?: nextPending(device, null)
        if (job == null) {
            return null
        }
        Map payload = composePayload(device, job)
        if (payload == null) {
            return null
        }
        job.status = JobStatus.DELIVERED
        job.deliveredAt = new Date()
        job.save(failOnError: true)
        [id: job.uuid, type: job.type.name(), payload: payload]
    }

    /** Oldest deliverable PENDING job — scheduled ones wait for their time. */
    private static Job nextPending(Device device, JobType type) {
        List<Job> jobs = Job.createCriteria().list(max: 1) {
            eq('device', device)
            eq('status', JobStatus.PENDING)
            if (type != null) {
                eq('type', type)
            }
            or {
                isNull('runAfter')
                le('runAfter', new Date())
            }
            order('dateCreated', 'asc')
        } as List<Job>
        jobs ? jobs.first() : null
    }

    /**
     * Queues an APPLY_CONFIG job when the composed spec drifted from the last
     * delivered one, so UI changes (variables, roles, groups, synced commits)
     * reach the device by its next pull. A failed apply with an unchanged
     * spec is auto-retried at later check-ins, bounded by
     * svenager.jobs.maxAttempts; a cancelled one stays an operator decision.
     */
    private void ensureConverged(Device device) {
        if (Job.countByDeviceAndTypeAndStatusInList(device, JobType.APPLY_CONFIG,
                [JobStatus.PENDING, JobStatus.DELIVERED, JobStatus.RUNNING])) {
            return
        }
        Map spec = composeSpec(device)
        if (!spec.plays) {
            return
        }
        Job last = Job.findByDeviceAndTypeAndSpecHashIsNotNull(device, JobType.APPLY_CONFIG,
                [sort: 'dateCreated', order: 'desc'])
        if (last?.specHash != specHash(spec)) {
            new Job(device: device, type: JobType.APPLY_CONFIG, triggeredBy: 'auto (config changed)')
                    .save(failOnError: true)
            return
        }
        int bound = maxAttempts()
        if (last.status in [JobStatus.FAILED, JobStatus.TIMED_OUT] && last.attempt < bound) {
            new Job(device: device, type: JobType.APPLY_CONFIG, attempt: last.attempt + 1,
                    triggeredBy: "auto (retry ${last.attempt + 1} of ${bound})")
                    .save(failOnError: true)
        }
    }

    private int maxAttempts() {
        grailsApplication.config.getProperty('svenager.jobs.maxAttempts', Integer, 3)
    }

    /** Alert only once auto-retries are exhausted — not for every attempt. */
    private void notifyIfFinalFailure(Job job) {
        if (job.type == JobType.APPLY_CONFIG && job.attempt >= maxAttempts()) {
            notificationService?.jobFailed(job)
        }
    }

    /** Canonical fingerprint of what an apply would do on the device. */
    private static String specHash(Map spec) {
        String canonical = JsonOutput.toJson([plays: spec.plays, extraVars: spec.extraVars])
        java.security.MessageDigest.getInstance('SHA-256')
                .digest(canonical.getBytes('UTF-8')).encodeHex().toString()
    }

    /** Builds the wire payload per job type, cancelling undeliverable jobs. */
    private Map composePayload(Device device, Job job) {
        switch (job.type) {
            case JobType.APPLY_CONFIG:
                Map spec = composeSpec(device)
                if (!spec.plays) {
                    cancel(job, 'Device has no assigned roles — nothing to apply.')
                    return null
                }
                job.payloadJson = JsonOutput.toJson(redactSecrets(spec))
                job.specHash = specHash(spec)
                return spec
            case JobType.CHECK_CONFIG:
                // Same composed spec, but no specHash: a preview must not
                // count as a delivered apply for drift detection.
                Map checkSpec = composeSpec(device)
                if (!checkSpec.plays) {
                    cancel(job, 'Device has no assigned roles — nothing to preview.')
                    return null
                }
                job.payloadJson = JsonOutput.toJson(redactSecrets(checkSpec))
                return checkSpec
            case JobType.AGENT_UPDATE:
                return new JsonSlurper().parseText(job.payloadJson ?: '{}') as Map
            case JobType.OPEN_TUNNEL:
                Map stored = new JsonSlurper().parseText(job.payloadJson) as Map
                RemoteSession session = RemoteSession.findByUuid(stored.sessionId as String)
                if (session == null || session.status != RemoteSessionStatus.PENDING || session.expired) {
                    cancel(job, 'Remote session ended before the device picked it up.')
                    return null
                }
                long remaining = (session.expiresAt.time - System.currentTimeMillis()).intdiv(1000L)
                return stored + [maxSeconds: Math.max(remaining, 1L)]
            default:
                return [:]
        }
    }

    private static void cancel(Job job, String reason) {
        job.status = JobStatus.CANCELLED
        job.error = reason
        job.finishedAt = new Date()
        job.save(failOnError: true)
    }

    /**
     * The composed spec for a device: per-repository plays (base roles first,
     * then assigned roles in group/position order) and merged extra-vars
     * (group vars in group-name order, then device vars override).
     * Secret values are decrypted — only ever ship this over TLS.
     */
    Map composeSpec(Device device) {
        List<DeviceGroup> groups = groupService.groupsOf(device)
        List<DiscoveredRole> orderedRoles = []
        groups.each { DeviceGroup group ->
            groupService.assignmentsOf(group).each { GroupRoleAssignment assignment ->
                if (assignment.enabled && !assignment.role.missing && !(assignment.role in orderedRoles)) {
                    orderedRoles << assignment.role
                }
            }
        }

        // One play per repository, in order of first appearance; base roles
        // of each involved repository run first within its play.
        List<Map> plays = []
        orderedRoles*.repository.unique { it.id }.each { AnsibleRepository repo ->
            List<String> baseNames = DiscoveredRole
                    .findAllByRepositoryAndBaseRoleAndMissing(repo, true, false)
                    .sort { it.name }*.name
            List<String> assignedNames = orderedRoles.findAll { it.repository.id == repo.id }*.name
            plays << [
                    repoId  : repo.id,
                    repoName: repo.name,
                    commit  : repo.lastCommit,
                    roles   : (baseNames + assignedNames).unique(),
            ]
        }

        Map extraVars = [:]
        Map<String, Boolean> secretByName = [:]
        groups.each { DeviceGroup group ->
            ConfigVariable.findAllByDeviceGroup(group).sort { it.name }.each { mergeVar(extraVars, secretByName, it) }
        }
        ConfigVariable.findAllByDevice(device).sort { it.name }.each { mergeVar(extraVars, secretByName, it) }
        // Per-device identity from the UI — not overridable by group/device
        // vars; a rename changes the spec hash and rolls out automatically.
        if (device.hostname) {
            extraVars.svenager_hostname = device.hostname
        }

        [
                timeoutSeconds: grailsApplication.config.getProperty('svenager.jobs.timeoutSeconds', Integer, DEFAULT_TIMEOUT_SECONDS),
                plays         : plays,
                extraVars     : extraVars,
                secretVars    : secretByName.findAll { it.value }.keySet().sort(),
        ]
    }

    private void mergeVar(Map extraVars, Map<String, Boolean> secretByName, ConfigVariable variable) {
        String json = variable.secret ? cryptoService.decrypt(variable.valueJson) : variable.valueJson
        extraVars[variable.name] = new JsonSlurper().parseText(json)
        secretByName[variable.name] = variable.secret
    }

    private static Map redactSecrets(Map spec) {
        Map redacted = new LinkedHashMap(spec)
        redacted.extraVars = (spec.extraVars as Map).collectEntries { name, value ->
            [name, (name in (spec.secretVars as Collection)) ? '***' : value]
        }
        redacted
    }

    private void timeoutStuckJobs(Device device) {
        Job.findAllByDeviceAndStatusInListAndDeliveredAtLessThan(
                device, [JobStatus.DELIVERED, JobStatus.RUNNING], stuckCutoff()).each { markTimedOut(it) }
    }

    private void markTimedOut(Job job) {
        job.status = JobStatus.TIMED_OUT
        job.error = 'No completion reported within the job timeout.'
        job.finishedAt = new Date()
        job.save(failOnError: true)
        notifyIfFinalFailure(job)
    }

    /**
     * Same cutoff across all devices — a device that never checks in again
     * would otherwise pin its jobs in DELIVERED/RUNNING forever.
     */
    int timeoutStuckJobsGlobally() {
        List<Job> stuck = Job.findAllByStatusInListAndDeliveredAtLessThan(
                [JobStatus.DELIVERED, JobStatus.RUNNING], stuckCutoff())
        stuck.each { markTimedOut(it) }
        stuck.size()
    }

    private Date stuckCutoff() {
        int timeout = grailsApplication.config.getProperty('svenager.jobs.timeoutSeconds', Integer, DEFAULT_TIMEOUT_SECONDS)
        new Date(System.currentTimeMillis() - (timeout + 300) * 1000L)
    }

    // --- agent events ------------------------------------------------------

    /**
     * Processes a status/log event from the agent. Log chunks are idempotent
     * by sequence number. Returns false if the job does not belong to the
     * device or is already finished.
     */
    boolean handleEvent(Device device, String jobRef, Map event) {
        // Jobs are addressed by uuid; numeric ids keep pre-uuid agents'
        // in-flight jobs working across the upgrade.
        Job job = Job.findByUuid(jobRef) ?: (jobRef?.isLong() ? Job.get(jobRef.toLong()) : null)
        applyEvent(device, job, event)
    }

    boolean handleEvent(Device device, Long jobId, Map event) {
        applyEvent(device, Job.get(jobId), event)
    }

    private boolean applyEvent(Device device, Job job, Map event) {
        if (job == null || job.device.id != device.id) {
            return false
        }
        switch (event.event) {
            case 'started':
                if (job.status.terminal) return false
                job.status = JobStatus.RUNNING
                job.startedAt = job.startedAt ?: new Date()
                break
            case 'log':
                // Older agents omit seq 0 entirely (JSON omitempty).
                int seq = (event.seq ?: 0) as int
                if (event.chunk && !JobLogChunk.findByJobAndSeq(job, seq)) {
                    JobLogChunk chunk = new JobLogChunk(job: job, seq: seq)
                    // Direct assignment: the map constructor's data binding
                    // would trim whitespace and corrupt log output.
                    chunk.content = event.chunk as String
                    chunk.save(failOnError: true)
                }
                break
            case 'finished':
                if (job.status.terminal) return false
                job.exitCode = event.exitCode as Integer
                job.status = (job.exitCode == 0) ? JobStatus.SUCCEEDED : JobStatus.FAILED
                if (event.error) {
                    job.error = event.error as String
                }
                job.finishedAt = new Date()
                device.lastJobAt = job.finishedAt
                device.save(failOnError: true)
                if (job.status == JobStatus.FAILED) {
                    notifyIfFinalFailure(job)
                }
                break
            default:
                return false
        }
        job.save(failOnError: true)
        true
    }

    String logOf(Job job) {
        JobLogChunk.findAllByJob(job).sort { it.seq }*.content.join('')
    }
}
