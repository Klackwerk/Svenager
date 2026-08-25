import { useEffect, useRef, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Spinner from 'react-bootstrap/Spinner'
import Table from 'react-bootstrap/Table'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useCancelJob, useJob, useRerunJob } from '../api/hooks'
import JobStatusBadge from '../components/JobStatusBadge'
import { useToast } from '../components/ToastProvider'
import { useExpertMode } from '../lib/expertMode'
import { absoluteTime, relativeTime } from '../lib/time'

export default function JobDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { data: job, isLoading, isError, refetch } = useJob(id ?? '')
  const { expert } = useExpertMode()
  const cancelJob = useCancelJob()
  const rerunJob = useRerunJob()
  const [logCopied, setLogCopied] = useState(false)
  const logRef = useRef<HTMLPreElement>(null)

  const running = job && !['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(job.status)

  useEffect(() => {
    if (running && logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [job?.log, running])

  if (isError) {
    return (
      <Alert variant="danger">
        This job could not be loaded — it may have been deleted.{' '}
        <Button size="sm" variant="outline-danger" onClick={() => refetch()}>
          Try again
        </Button>{' '}
        <Link to="/jobs">Back to jobs</Link>
      </Alert>
    )
  }

  if (isLoading || !job) {
    return <Spinner role="status" aria-label="Loading job" />
  }

  return (
    <>
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <Link to="/jobs">Jobs</Link>
          </li>
          <li className="breadcrumb-item active" aria-current="page">
            #{job.id.slice(0, 8)}
          </li>
        </ol>
      </nav>

      <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
        <h1 className="h3 mb-0">
          Job #{job.id.slice(0, 8)} on <Link to={`/devices/${job.deviceId}`}>{job.hostname}</Link>
        </h1>
        <JobStatusBadge status={job.status} />
        {job.type === 'CHECK_CONFIG' && (
          <Badge bg="info" text="dark" title="Check mode: showed what would change without changing anything">
            Preview
          </Badge>
        )}
        {running && (
          <Button
            size="sm"
            variant="outline-danger"
            className="ms-auto"
            disabled={cancelJob.isPending}
            onClick={() => cancelJob.mutate(job.id)}
          >
            {cancelJob.isPending ? 'Cancelling…' : 'Cancel job'}
          </Button>
        )}
        {!running && job.type === 'APPLY_CONFIG' && (
          <Button
            size="sm"
            className="ms-auto"
            disabled={rerunJob.isPending}
            onClick={() =>
              rerunJob.mutate(job.id, { onSuccess: (created) => navigate(`/jobs/${created.id}`) })
            }
          >
            {rerunJob.isPending ? 'Queuing…' : 'Re-run'}
          </Button>
        )}
      </div>

      {job.retriesExhausted && (
        <Alert variant="warning">
          <strong>Automatic retries are exhausted</strong> (attempt {job.attempt} of {job.maxAttempts}). Svenager
          only re-queues this configuration on its own once it changes. Fix the cause on the device, then{' '}
          <strong>Re-run</strong> — that starts a fresh attempt counter.
        </Alert>
      )}

      <Card className="mb-3">
        <Card.Body>
          <Table size="sm" responsive className="mb-0">
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
              {job.payload && (
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
              {expert && job.payload && Object.keys(job.payload.extraVars).length > 0 && (
                <tr>
                  <th scope="row">Variables</th>
                  <td style={{ wordBreak: 'break-word' }}>
                    <code>{JSON.stringify(job.payload.extraVars)}</code>
                  </td>
                </tr>
              )}
            </tbody>
          </Table>
        </Card.Body>
      </Card>

      <Card>
        <Card.Body>
          <Card.Title className="h6 d-flex align-items-center gap-2">
            Log
            {running && <Spinner size="sm" role="status" aria-label="Job running" />}
            {job.log && (
              <span className="ms-auto d-flex gap-2">
                <Button
                  size="sm"
                  variant={logCopied ? 'success' : 'outline-secondary'}
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(job.log)
                      setLogCopied(true)
                    } catch {
                      toast({ variant: 'danger', text: 'Copying failed — select the log and copy it manually.' })
                    }
                  }}
                >
                  {logCopied ? 'Copied ✓' : 'Copy log'}
                </Button>
                <Button
                  size="sm"
                  variant="outline-secondary"
                  onClick={() => {
                    const url = URL.createObjectURL(new Blob([job.log], { type: 'text/plain' }))
                    const anchor = document.createElement('a')
                    anchor.href = url
                    anchor.download = `job-${job.id}-${job.hostname}.log`
                    anchor.click()
                    URL.revokeObjectURL(url)
                  }}
                >
                  Download log
                </Button>
              </span>
            )}
          </Card.Title>
          <pre
            ref={logRef}
            className="bg-dark text-light rounded p-3 mb-0"
            style={{ maxHeight: '32rem', overflow: 'auto', whiteSpace: 'pre-wrap' }}
          >
            {job.log || (running ? 'Waiting for output…' : 'No output was recorded.')}
          </pre>
        </Card.Body>
      </Card>
    </>
  )
}
