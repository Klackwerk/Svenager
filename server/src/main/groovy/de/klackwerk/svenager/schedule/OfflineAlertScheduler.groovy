package de.klackwerk.svenager.schedule

import de.klackwerk.svenager.AlertService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.util.concurrent.TimeUnit

/** Periodic offline detection — alerts fire once per state transition. */
@Slf4j
@Component
class OfflineAlertScheduler {

    @Autowired
    AlertService alertService

    @Scheduled(initialDelay = 1L, fixedDelay = 1L, timeUnit = TimeUnit.MINUTES)
    void sweep() {
        int sent = alertService.sweepOfflineAlerts()
        if (sent) {
            log.info('sent {} offline/recovery alerts', sent)
        }
    }
}
