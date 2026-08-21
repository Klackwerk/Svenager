import { useState, type ReactNode } from 'react'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import type { RoleInfo } from '../api/types'

interface ApplyConfirmModalProps {
  show: boolean
  onHide: () => void
  /** runAfter: ISO instant for "Apply at…", null for "Apply now". */
  onConfirm: (runAfter: string | null) => void
  pending: boolean
  /** Roles in run order (effective roles of the device or group). */
  roles: RoleInfo[]
  deviceCount: number
  offlineCount: number
  triggeredBy?: string | null
  /** Extra controls (e.g. canary/schedule options) above the footer. */
  children?: ReactNode
}

/**
 * Blast-radius confirmation shown before any configuration apply: what runs,
 * in which order, on how many devices, and how many of those are offline.
 */
export default function ApplyConfirmModal({
  show,
  onHide,
  onConfirm,
  pending,
  roles,
  deviceCount,
  offlineCount,
  triggeredBy,
  children,
}: ApplyConfirmModalProps) {
  const nothingToRun = roles.length === 0 || deviceCount === 0
  const [when, setWhen] = useState<'now' | 'later'>('now')
  const [at, setAt] = useState('')
  const atInPast = at !== '' && new Date(at).getTime() <= Date.now()
  const scheduleIncomplete = when === 'later' && (at === '' || atInPast)
  return (
    <Modal show={show} onHide={onHide} centered>
      <Modal.Header closeButton>
        <Modal.Title>Apply configuration?</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        {nothingToRun ? (
          <p className="mb-0 text-secondary">
            Nothing would run — {deviceCount === 0 ? 'no devices are affected' : 'no roles are assigned'}.
          </p>
        ) : (
          <>
            <p className="mb-2">These roles run in this order:</p>
            <ol className="mb-3">
              {roles.map((role) => (
                <li key={role.id}>{role.displayName}</li>
              ))}
            </ol>
            <p className="mb-2">
              Affected: <strong>{deviceCount}</strong> {deviceCount === 1 ? 'device' : 'devices'}
              {offlineCount > 0 && (
                <>
                  {' '}
                  — <strong>{offlineCount}</strong> currently offline; they apply at their next check-in.
                </>
              )}
            </p>
            {triggeredBy && (
              <p className="mb-0 text-secondary small">The run is recorded as triggered by {triggeredBy}.</p>
            )}
            {children}
            <fieldset className="mt-3">
              <legend className="visually-hidden">When to apply</legend>
              <Form.Check
                type="radio"
                name="apply-when"
                id="apply-when-now"
                label="Apply now"
                checked={when === 'now'}
                onChange={() => setWhen('now')}
              />
              <Form.Check
                type="radio"
                name="apply-when"
                id="apply-when-later"
                label="Apply at…"
                checked={when === 'later'}
                onChange={() => setWhen('later')}
              />
              {when === 'later' && (
                <>
                  <Form.Control
                    type="datetime-local"
                    className="mt-1"
                    style={{ maxWidth: '16rem' }}
                    aria-label="Apply at"
                    value={at}
                    isInvalid={atInPast}
                    onChange={(e) => setAt(e.target.value)}
                  />
                  {atInPast && (
                    <Form.Control.Feedback type="invalid" role="alert">
                      Pick a time in the future.
                    </Form.Control.Feedback>
                  )}
                  <Form.Text className="text-secondary">
                    Devices pick the job up at their first check-in after this time.
                  </Form.Text>
                </>
              )}
            </fieldset>
          </>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={onHide}>
          Cancel
        </Button>
        <Button
          disabled={pending || nothingToRun || scheduleIncomplete}
          onClick={() => onConfirm(when === 'later' ? new Date(at).toISOString() : null)}
        >
          {pending ? 'Queuing…' : when === 'later' ? 'Schedule apply' : 'Apply configuration'}
        </Button>
      </Modal.Footer>
    </Modal>
  )
}
