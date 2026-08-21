package de.klackwerk.svenager.schedule

import de.klackwerk.svenager.AnsibleRepository
import de.klackwerk.svenager.RepoSyncService
import groovy.util.logging.Slf4j
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.util.concurrent.TimeUnit

/**
 * Background sync of every registered Ansible repository. Together with the
 * spec-drift check at agent check-in, an upstream push (or a moved tag)
 * reaches devices without anyone pressing a button.
 */
@Slf4j
@Component
class RepoSyncScheduler {

    private final RepoSyncService repoSyncService

    RepoSyncScheduler(RepoSyncService repoSyncService) {
        this.repoSyncService = repoSyncService
    }

    @Scheduled(initialDelayString = '${svenager.repos.syncIntervalSeconds:300}',
            fixedDelayString = '${svenager.repos.syncIntervalSeconds:300}',
            timeUnit = TimeUnit.SECONDS)
    void syncAll() {
        List<Long> ids = []
        AnsibleRepository.withTransaction {
            ids = AnsibleRepository.list()*.id
        }
        ids.each { Long id ->
            try {
                repoSyncService.sync(id)
            } catch (Exception e) {
                log.warn('Scheduled sync of repository {} failed', id, e)
            }
        }
    }
}
