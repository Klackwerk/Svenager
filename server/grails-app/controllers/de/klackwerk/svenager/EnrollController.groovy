package de.klackwerk.svenager

import grails.converters.JSON

class EnrollController {

    static allowedMethods = [enroll: 'POST', register: 'POST']

    EnrollmentService enrollmentService

    /**
     * Token-less pre-request: cloned/pre-imaged devices poll here until an
     * admin approves them. 202 while pending, 201 with credentials once,
     * 403 when denied.
     */
    def register() {
        def body = request.JSON
        try {
            Map result = enrollmentService.handleRegistration(
                    body?.requestId as String,
                    body?.hostname as String,
                    body?.facts instanceof Map ? body.facts as Map : null)
            response.status = result.status == 'approved' ? 201 : (result.status == 'pending' ? 202 : 403)
            render(result as JSON)
        } catch (EnrollmentException e) {
            response.status = 422
            render([error: e.message] as JSON)
        }
    }

    def enroll() {
        def body = request.JSON
        try {
            Map result = enrollmentService.enroll(
                    body?.enrollmentToken as String,
                    body?.hostname as String,
                    body?.facts instanceof Map ? body.facts as Map : null)
            response.status = 201
            render([deviceId: result.device.uuid, deviceToken: result.deviceToken] as JSON)
        } catch (EnrollmentException e) {
            response.status = 403
            render([error: e.message] as JSON)
        }
    }
}
