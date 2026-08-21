package de.klackwerk.svenager

import de.klackwerk.svenager.security.Tokens
import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput

@Transactional
class EnrollmentService {

    GroupService groupService

    /**
     * Creates an enrollment token. The raw token value is returned exactly
     * once and never stored — only its hash is persisted.
     */
    Map createToken(String label, Integer maxUses, Date expiresAt, String createdBy,
                    List<DeviceGroup> targetGroups = []) {
        String raw = Tokens.generate('svet')
        EnrollmentToken token = new EnrollmentToken(
                label: label,
                maxUses: maxUses ?: 1,
                expiresAt: expiresAt,
                createdBy: createdBy,
                tokenHash: Tokens.hash(raw),
        )
        targetGroups.each { token.addToTargetGroups(it) }
        token.save(failOnError: true)
        [token: raw, entity: token]
    }

    /**
     * Registers a new device against a valid enrollment token and issues its
     * per-device API token (returned raw exactly once, stored hashed).
     */
    Map enroll(String rawEnrollmentToken, String hostname, Map facts) {
        EnrollmentToken token = rawEnrollmentToken ?
                EnrollmentToken.findByTokenHash(Tokens.hash(rawEnrollmentToken)) : null
        if (token == null || !token.usable) {
            throw new EnrollmentException('invalid, expired or exhausted enrollment token')
        }
        token.usedCount++
        token.save(failOnError: true)

        String deviceToken = Tokens.generate('svdt')
        Device device = new Device(
                hostname: hostname ?: 'unknown',
                tokenHash: Tokens.hash(deviceToken),
                factsJson: facts ? JsonOutput.toJson(facts) : null,
                lastContactAt: new Date(),
        ).save(failOnError: true)
        // Target groups: the device converges at its first check-in instead
        // of arriving ungrouped (the drift check queues the apply).
        token.targetGroups?.each { groupService.addDevice(it, device) }
        [device: device, deviceToken: deviceToken]
    }

    // --- approval-based enrollment (token-less pre-request) -----------------

    /**
     * Handles one poll of a token-less device. Returns the wire response:
     * pending/denied advice, or — once an admin approved — the one-time
     * device credentials (the request is then completed).
     */
    Map handleRegistration(String requestId, String hostname, Map facts) {
        if (!requestId || requestId.length() > 128) {
            throw new EnrollmentException('requestId is required (e.g. the machine id)')
        }
        EnrollmentRequest request = EnrollmentRequest.findByRequestId(requestId)
        if (request == null) {
            request = new EnrollmentRequest(requestId: requestId)
        }
        request.lastSeenAt = new Date()
        if (hostname) {
            request.hostname = hostname
        }
        if (facts) {
            request.factsJson = JsonOutput.toJson(facts)
        }

        switch (request.status) {
            case EnrollmentRequestStatus.APPROVED:
                String deviceToken = Tokens.generate('svdt')
                Device device = new Device(
                        hostname: request.hostname ?: 'unknown',
                        tokenHash: Tokens.hash(deviceToken),
                        factsJson: request.factsJson,
                        lastContactAt: new Date(),
                ).save(failOnError: true)
                request.status = EnrollmentRequestStatus.COMPLETED
                request.device = device
                request.save(failOnError: true)
                return [status: 'approved', deviceId: device.uuid, deviceToken: deviceToken]
            case EnrollmentRequestStatus.DENIED:
                request.save(failOnError: true)
                return [status: 'denied']
            case EnrollmentRequestStatus.COMPLETED:
                // The credentials were already handed out once — never twice.
                request.save(failOnError: true)
                return [status: 'denied']
            default:
                request.save(failOnError: true)
                return [status: 'pending']
        }
    }

    void decideRequest(String uuid, boolean approved, String decidedBy) {
        EnrollmentRequest request = EnrollmentRequest.findByUuid(uuid)
        if (request == null || request.status != EnrollmentRequestStatus.PENDING) {
            throw new EnrollmentException('request not found or already decided')
        }
        request.status = approved ? EnrollmentRequestStatus.APPROVED : EnrollmentRequestStatus.DENIED
        request.decidedBy = decidedBy
        request.decidedAt = new Date()
        request.save(failOnError: true)
    }

    @Transactional(readOnly = true)
    List<EnrollmentRequest> listRequests() {
        EnrollmentRequest.list(sort: 'dateCreated', order: 'desc', max: 100)
    }

    @Transactional(readOnly = true)
    List<EnrollmentToken> listTokens() {
        EnrollmentToken.list(sort: 'dateCreated', order: 'desc')
    }

    void revoke(String uuid) {
        EnrollmentToken token = EnrollmentToken.findByUuid(uuid)
        if (token != null) {
            token.revoked = true
            token.save(failOnError: true)
        }
    }
}
