package de.klackwerk.svenager

import grails.converters.JSON

/** Admin CRUD for alert channels plus a synchronous test delivery. */
class NotificationChannelController {

    static allowedMethods = [index: 'GET', save: 'POST', update: 'PUT', delete: 'DELETE', test: 'POST']

    NotificationService notificationService

    def index() {
        render(NotificationChannel.list(sort: 'name').collect { summarize(it) } as JSON)
    }

    def save() {
        def body = request.JSON
        String name = body?.name?.toString()?.trim()
        String type = body?.type?.toString()
        String target = body?.target?.toString()?.trim()
        if (!name || !(type in ['EMAIL', 'WEBHOOK']) || !target) {
            respondError(422, 'name, type (EMAIL|WEBHOOK) and target are required')
            return
        }
        if (type == 'WEBHOOK' && !(target ==~ /https?:\/\/.+/)) {
            respondError(422, 'webhook target must be an http(s) URL')
            return
        }
        if (NotificationChannel.findByName(name)) {
            respondError(409, 'a channel with this name already exists')
            return
        }
        NotificationChannel channel = null
        NotificationChannel.withTransaction {
            channel = new NotificationChannel(name: name, type: type, target: target)
                    .save(failOnError: true, flush: true)
        }
        response.status = 201
        render(summarize(channel) as JSON)
    }

    def update(String id) {
        NotificationChannel channel = NotificationChannel.findByUuid(id)
        if (channel == null) {
            respondError(404, 'channel not found')
            return
        }
        def body = request.JSON
        NotificationChannel.withTransaction {
            if (body?.containsKey('enabled')) {
                channel.enabled = body.enabled == true
            }
            if (body?.name) {
                channel.name = body.name.toString().trim()
            }
            if (body?.target) {
                channel.target = body.target.toString().trim()
            }
            channel.save(failOnError: true, flush: true)
        }
        render(summarize(channel) as JSON)
    }

    def delete(String id) {
        NotificationChannel channel = NotificationChannel.findByUuid(id)
        if (channel == null) {
            respondError(404, 'channel not found')
            return
        }
        NotificationChannel.withTransaction { channel.delete(flush: true) }
        response.status = 204
        render('')
    }

    /** Sends a test alert through this one channel, reporting failures. */
    def test(String id) {
        NotificationChannel channel = NotificationChannel.findByUuid(id)
        if (channel == null) {
            respondError(404, 'channel not found')
            return
        }
        try {
            notificationService.deliver(channel.type, channel.target,
                    'Svenager test alert', "This is a test alert for channel '${channel.name}'.")
            render([ok: true] as JSON)
        } catch (Exception e) {
            respondError(502, e.message ?: 'delivery failed')
        }
    }

    private static Map summarize(NotificationChannel channel) {
        [
                id       : channel.uuid,
                name     : channel.name,
                type     : channel.type,
                target   : channel.target,
                enabled  : channel.enabled,
                createdAt: channel.dateCreated?.toInstant()?.toString(),
        ]
    }

    private void respondError(int status, String message) {
        response.status = status
        render([error: message] as JSON)
    }
}
