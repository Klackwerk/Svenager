import { useEffect, useRef, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Modal from 'react-bootstrap/Modal'
import Spinner from 'react-bootstrap/Spinner'
import Table from 'react-bootstrap/Table'
import { Link, useSearchParams } from 'react-router-dom'
import { useCancelJob, useJob, useRerunJob } from '../api/hooks'
import { useExpertMode } from '../lib/expertMode'
import { JOB_PARAM } from '../lib/jobLink'
import { absoluteTime, relativeTime } from '../lib/time'
import JobStatusBadge from './JobStatusBadge'
import { useToast } from './ToastProvider'

const FINAL_STATES = ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED']

/**
 * Job details and live log as a dialog over the current page, driven by
 * the `?job=<id>` search parameter so it is linkable from anywhere and
 * the back button closes it. Mounted once in App.
 */
export default function JobModal() {
  const [params, setParams] = useSearchParams()
  const id = params.get(JOB_PARAM)

  const close = () => {
    const next = new URLSearchParams(params)
    next.delete(JOB_PARAM)
    setParams(next)
  }
  const show = (jobId: string) => {
    const next = new URLSearchParams(params)
    next.set(JOB_PARAM, jobId)
    setParams(next)
  }

  return (
    <Modal show={id != null} onHide={close} size="xl" scrollable aria-labelledby="job-modal-title">
      {id && <JobModalContent id={id} onClose={close} onShowJob={show} />}
    </Modal>
  )
}

interface ContentProps {
  id: string
  onClose: () => void
  onShowJob: (id: string) => void
}

function JobModalContent({ id, onClose, onShowJob }: ContentProps) {
  const toast = useToast()
  const { data: job, isLoading, isError, refetch } = useJob(id)
  const { expert, setExpert } = useExpertMode()
  const cancelJob = useCancelJob()
  const rerunJob = useRerunJob()
  const [logCopied, setLogCopied] = useState(false)
  const logRef = useRef<HTMLPreElement>(null)

  const running = job && !FINAL_STATES.includes(job.status)

  useEffect(() => {
    if (running && logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [job?.log, running])

  if (isError) {
    return (
      <>
        <Modal.Header closeButton>
          <Modal.Title id="job-modal-title">Job</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Alert variant="danger" className="mb-0">
            This job could not be loaded — it may have been deleted.{' '}
            <Button size="sm" variant="outline-danger" onClick={() => refetch()}>
              Try again
            </Button>
          </Alert>
        </Modal.Body>
      </>
    )
  }

  if (isLoading || !job) {
    return (
      <>
        <Modal.Header closeButton>
          <Modal.Title id="job-modal-title">Job</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Spinner role="status" aria-label="Loading job" />
        </Modal.Body>
      </>
    )
  }

  const copyLog = async () => {
    try {
      await navigator.clipboard.writeText(job.log)
      setLogCopied(true)
    } catch {
      toast({ variant: 'danger', text: 'Copying failed — select the log and copy it manually.' })
    }
  }

  const downloadLog = () => {
    const url = URL.createObjectURL(new Blob([job.log], { type: 'text/plain' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `job-${job.id}-${job.hostname}.log`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <Modal.Header closeButton>
        <Modal.Title id="job-modal-title" className="h5 d-flex align-items-center gap-2 flex-wrap">
          Job #{job.id.slice(0, 8)} on <Link to={`/devices/${job.deviceId}`}>{job.hostname}</Link>
          <JobStatusBadge status={job.status} />
          {job.type === 'CHECK_CONFIG' && (
            <Badge bg="info" text="dark" title="Check mode: showed what would change without changing anything">
              Preview
            </Badge>
          )}
          {running && <Spinner size="sm" role="status" aria-label="Job running" />}
        </Modal.Title>
      </Modal.Header>

      <Modal.Body>
        {job.retriesExhausted && (
          <Alert variant="warning">
            <strong>Automatic retries are exhausted</strong> (attempt {job.attempt} of {job.maxAttempts}). Svenager
            only re-queues this configuration on its own once it changes. Fix the cause on the device, then{' '}
            <strong>Re-run</strong> — that starts a fresh attempt counter.
          </Alert>
        )}

        <Table size="sm" responsive className="job-details mb-2">
          <tbody>
            <tr>
              <th scope="row">Queued</th>
              <td title={absoluteTime(job.queuedAt)}>
                {relativeTime(job.queuedAt)}
                {job.triggeredBy ? ` by ${job.triggeredBy}` : ''}
              </td>
            </tr>
            {job.runAfter && job.status === 'PENDING' && (
              <tr>
                <th scope="row">Scheduled</th>
                <td>not before {absoluteTime(job.runAfter)}</td>
              </tr>
            )}
            <tr>
              <th scope="row">Started / finished</th>
              <td>
                {relativeTime(job.startedAt)} / {relativeTime(job.finishedAt)}
              </td>
            </tr>
            {job.type === 'APPLY_CONFIG' && job.attempt > 1 && (
              <tr>
                <th scope="row">Attempt</th>
                <td>
                  {job.attempt} of {job.maxAttempts}
                </td>
              </tr>
            )}
            {job.exitCode != null && (
              <tr>
                <th scope="row">Exit code</th>
                <td>{job.exitCode}</td>
              </tr>
            )}
            {job.error && (
              <tr>
                <th scope="row">Error</th>
                <td className="text-danger">{job.error}</td>
              </tr>
            )}
            {job.type === 'AGENT_UPDATE' && (
              <tr>
                <th scope="row">Target version</th>
                <td>{job.payload?.version || 'latest available'}</td>
              </tr>
            )}
            {job.payload?.plays && (
              <tr>
                <th scope="row">Runs</th>
                <td>
                  {job.payload.plays.map((play) => (
                    <div key={play.repoId}>
                      {play.repoName}
                      {expert && (
                        <>
                          {' '}
                          @ <code>{play.commit?.slice(0, 10)}</code>
                        </>
                      )}
                      : {play.roles.join(' → ')}
                    </div>
                  ))}
                </td>
              </tr>
            )}
            {expert && job.payload?.extraVars && Object.keys(job.payload.extraVars).length > 0 && (
              <tr>
                <th scope="row">Variables</th>
                <td style={{ wordBreak: 'break-word' }}>
                  <code>{JSON.stringify(job.payload.extraVars)}</code>
                </td>
              </tr>
            )}
          </tbody>
        </Table>
        {!expert && (job.payload?.plays?.length || Object.keys(job.payload?.extraVars ?? {}).length > 0) && (
          <p className="text-secondary small">
            Commit hashes and raw variables are hidden.{' '}
            <Button variant="link" size="sm" className="p-0 align-baseline" onClick={() => setExpert(true)}>
              Show expert details
            </Button>
          </p>
        )}

        <div className="d-flex align-items-center gap-2 mb-2">
          <span className="fw-semibold">Log</span>
          {job.log && (
            <span className="ms-auto d-flex gap-2">
              <Button size="sm" variant={logCopied ? 'success' : 'outline-secondary'} onClick={copyLog}>
                {logCopied ? 'Copied ✓' : 'Copy log'}
              </Button>
              <Button size="sm" variant="outline-secondary" onClick={downloadLog}>
                Download log
              </Button>
            </span>
          )}
        </div>
        <pre
          ref={logRef}
          className="bg-dark text-light rounded p-3 mb-0"
          style={{ maxHeight: '50vh', overflow: 'auto', whiteSpace: 'pre-wrap' }}
        >
          {job.log || (running ? 'Waiting for output…' : 'No output was recorded.')}
        </pre>
      </Modal.Body>

      <Modal.Footer>
        {running && (
          <Button
            variant="outline-danger"
            className="me-auto"
            disabled={cancelJob.isPending}
            onClick={() => cancelJob.mutate(job.id)}
          >
            {cancelJob.isPending ? 'Cancelling…' : 'Cancel job'}
          </Button>
        )}
        {!running && job.type === 'APPLY_CONFIG' && (
          <Button
            className="me-auto"
            disabled={rerunJob.isPending}
            onClick={() => rerunJob.mutate(job.id, { onSuccess: (created) => onShowJob(created.id) })}
          >
            {rerunJob.isPending ? 'Queuing…' : 'Re-run'}
          </Button>
        )}
        <Button variant="secondary" onClick={onClose}>
          Close
        </Button>
      </Modal.Footer>
    </>
  )
}
