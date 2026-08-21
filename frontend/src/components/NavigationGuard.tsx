import { useEffect } from 'react'
import Button from 'react-bootstrap/Button'
import Modal from 'react-bootstrap/Modal'
import { useBlocker } from 'react-router-dom'

/**
 * Blocks in-app navigation (and warns on tab close) while `when` is true,
 * asking before unsaved edits are discarded. Needs a data router.
 */
export default function NavigationGuard({ when }: { when: boolean }) {
  const blocker = useBlocker(when)

  useEffect(() => {
    if (!when) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [when])

  useEffect(() => {
    if (blocker.state === 'blocked' && !when) blocker.reset()
  }, [blocker, when])

  return (
    <Modal show={blocker.state === 'blocked'} onHide={() => blocker.reset?.()} centered>
      <Modal.Header closeButton>
        <Modal.Title>Unsaved changes</Modal.Title>
      </Modal.Header>
      <Modal.Body>Your variable edits are not saved yet. Leave this page and discard them?</Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={() => blocker.reset?.()}>
          Stay and keep editing
        </Button>
        <Button variant="danger" onClick={() => blocker.proceed?.()}>
          Discard changes
        </Button>
      </Modal.Footer>
    </Modal>
  )
}
