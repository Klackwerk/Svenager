package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

/**
 * Offline detection with per-transition state: one alert when a device has
 * been silent past its threshold, one recovery alert when it returns.
 */
@Transactional
class AlertService {

    static final int DEFAULT_OFFLINE_AFTER_SECONDS = 600

    GrailsApplication grailsApplication
    GroupService groupService
    NotificationService notificationService

    /**
     * A group can lengthen the alert threshold (e.g. notebooks that roam);
     * a device in several overriding groups uses the most tolerant one.
     */
    int effectiveOfflineAfterSeconds(Device device) {
        List<Integer> overrides = groupService.groupsOf(device)*.offlineAlertSeconds.findAll()
        overrides ? overrides.max()
                : grailsApplication.config.getProperty('svenager.alerts.offlineAfterSeconds', Integer,
                DEFAULT_OFFLINE_AFTER_SECONDS)
    }

    /** Returns how many alerts (offline + recovery) were sent this sweep. */
    int sweepOfflineAlerts() {
        int sent = 0
        long now = System.currentTimeMillis()
        Device.list().each { Device device ->
            if (device.status != DeviceStatus.ACTIVE || device.lastContactAt == null) {
                return
            }
            int afterSeconds = effectiveOfflineAfterSeconds(device)
            boolean overdue = device.lastContactAt.time < now - afterSeconds * 1000L
            if (overdue && device.offlineAlertedAt == null) {
                device.offlineAlertedAt = new Date()
                device.save(failOnError: true)
                notificationService.deviceOffline(device, afterSeconds)
                sent++
            } else if (!overdue && device.offlineAlertedAt != null) {
                device.offlineAlertedAt = null
                device.save(failOnError: true)
                notificationService.deviceRecovered(device)
                sent++
            }
        }
        sent
    }
}
