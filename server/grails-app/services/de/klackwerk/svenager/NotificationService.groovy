package de.klackwerk.svenager

import grails.core.GrailsApplication
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Delivers alerts to the configured channels (email, webhook). Broadcasts
 * are dispatched on a background thread and never fail the caller; the
 * synchronous deliver() backs the per-channel test button.
 */
@Slf4j
class NotificationService {

    GrailsApplication grailsApplication

    /** Present only when spring.mail.host is configured. */
    @Autowired(required = false)
    JavaMailSender mailSender

    private final ExecutorService executor = Executors.newSingleThreadExecutor { Runnable r ->
        Thread thread = new Thread(r, 'svenager-notify')
        thread.daemon = true
        thread
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Sends to every enabled channel; failures are logged, never thrown. */
    void broadcast(String subject, String body) {
        List<Map> channels = NotificationChannel.findAllByEnabled(true).collect {
            [name: it.name, type: it.type, target: it.target]
        }
        channels.each { Map channel ->
            executor.submit {
                try {
                    deliver(channel.type as String, channel.target as String, subject, body)
                } catch (Exception e) {
                    log.warn('notification via channel "{}" failed: {}', channel.name, e.message)
                }
            }
        }
    }

    /** Synchronous single-channel delivery; throws on failure. */
    void deliver(String type, String target, String subject, String body) {
        if (type == 'EMAIL') {
            if (mailSender == null) {
                throw new IllegalStateException('no SMTP server is configured (spring.mail.host)')
            }
            SimpleMailMessage message = new SimpleMailMessage()
            message.from = grailsApplication.config.getProperty('svenager.alerts.from', String,
                    'svenager@localhost')
            message.to = target
            message.subject = subject
            message.text = body
            mailSender.send(message)
        } else {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(10))
                    .header('Content-Type', 'application/json')
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JsonOutput.toJson([subject: subject, text: body, source: 'svenager'])))
                    .build()
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("webhook returned HTTP ${response.statusCode()}")
            }
        }
    }

    void deviceOffline(Device device, int afterSeconds) {
        broadcast("Device offline: ${device.hostname}",
                "${device.hostname} has not checked in for more than ${afterSeconds} seconds.\n" +
                        "Last contact: ${device.lastContactAt}\nDevice id: ${device.uuid}")
    }

    void deviceRecovered(Device device) {
        broadcast("Device back online: ${device.hostname}",
                "${device.hostname} checked in again.\nDevice id: ${device.uuid}")
    }

    void jobFailed(Job job) {
        String kind = job.status == JobStatus.TIMED_OUT ? 'timed out' : 'failed'
        broadcast("Apply ${kind} on ${job.device.hostname}",
                "Job #${job.id} (${job.type.name()}) on ${job.device.hostname} ${kind} " +
                        "after ${job.attempt} attempt${job.attempt == 1 ? '' : 's'}." +
                        (job.error ? "\nError: ${job.error}" : '') +
                        (job.exitCode != null ? "\nExit code: ${job.exitCode}" : ''))
    }

    void repoSyncFailed(AnsibleRepository repo, String message) {
        broadcast("Repository sync failed: ${repo.name}",
                "Syncing '${repo.name}' (${repo.gitUrl}) failed:\n${message ?: 'unknown error'}")
    }
}
