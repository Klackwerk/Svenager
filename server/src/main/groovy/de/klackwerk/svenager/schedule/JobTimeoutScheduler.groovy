package de.klackwerk.svenager.schedule

import de.klackwerk.svenager.Job
import de.klackwerk.svenager.JobService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.util.concurrent.TimeUnit

/**
 * Times out DELIVERED/RUNNING jobs whose device went silent. The check-in
 * path only sweeps jobs of the device that checks in, so an offline device
 * would otherwise pin its jobs as active forever.
 */
@Slf4j
@Component
class JobTimeoutScheduler {

    @Autowired
    JobService jobService

    @Scheduled(initialDelay = 2L, fixedDelay = 5L, timeUnit = TimeUnit.MINUTES)
    void sweep() {
        Job.withTransaction {
            int timedOut = jobService.timeoutStuckJobsGlobally()
            if (timedOut) {
                log.info('timed out {} stuck jobs on silent devices', timedOut)
            }
        }
    }
}
