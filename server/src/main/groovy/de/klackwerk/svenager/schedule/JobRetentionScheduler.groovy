package de.klackwerk.svenager.schedule

import de.klackwerk.svenager.Job
import de.klackwerk.svenager.JobBatch
import de.klackwerk.svenager.JobLogChunk
import de.klackwerk.svenager.JobStatus
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.util.concurrent.TimeUnit

/**
 * Prunes finished jobs (and their log chunks) past the retention window so
 * job history and logs cannot grow without bound. Open jobs are never
 * touched; retentionDays <= 0 disables pruning entirely.
 */
@Slf4j
@Component
class JobRetentionScheduler {

    static final List<JobStatus> TERMINAL_STATUSES =
            [JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.TIMED_OUT, JobStatus.CANCELLED]

    @Value('${svenager.jobs.retentionDays:90}')
    int retentionDays

    @Scheduled(initialDelay = 5L, fixedDelay = 60L, timeUnit = TimeUnit.MINUTES)
    void prune() {
        if (retentionDays <= 0) {
            return
        }
        Date cutoff = new Date(System.currentTimeMillis() - retentionDays * 24L * 3600_000L)
        Job.withTransaction {
            int chunks = JobLogChunk.executeUpdate(
                    '''delete from JobLogChunk c where c.job.id in
                       (select j.id from Job j where j.status in (:statuses) and j.dateCreated < :cutoff)''',
                    [statuses: TERMINAL_STATUSES, cutoff: cutoff])
            int jobs = Job.executeUpdate(
                    'delete from Job j where j.status in (:statuses) and j.dateCreated < :cutoff',
                    [statuses: TERMINAL_STATUSES, cutoff: cutoff])
            int batches = JobBatch.executeUpdate(
                    '''delete from JobBatch b where b.dateCreated < :cutoff
                       and not exists (select 1 from Job j where j.batch = b)''',
                    [cutoff: cutoff])
            if (jobs || batches) {
                log.info('pruned {} jobs, {} log chunks and {} empty batches older than {} days',
                        jobs, chunks, batches, retentionDays)
            }
        }
    }
}
