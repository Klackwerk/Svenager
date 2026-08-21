import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Spinner from 'react-bootstrap/Spinner'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useBatch, useContinueRollout, useRetryBatch } from '../api/hooks'
import type { JobBatchInfo } from '../api/types'
import JobsTable from '../components/JobsTable'
import { absoluteTime, relativeTime } from '../lib/time'

function count(batch: JobBatchInfo, statuses: Array<keyof JobBatchInfo['counts']>): number {
  return statuses.reduce((sum, status) => sum + (batch.counts[status] ?? 0), 0)
}

export function rollup(batch: JobBatchInfo): { text: string; variant: string } {
  const succeeded = count(batch, ['SUCCEEDED'])
  const failed = count(batch, ['FAILED', 'TIMED_OUT', 'CANCELLED'])
  if (!batch.done) {
    const finished = succeeded + failed
    return {
      text: `${finished} of ${batch.total} devices finished — the rest apply at their next check-in.`,
      variant: 'info',
    }
  }
  if (failed === 0) {
    if (batch.stage === 'CANARY') {
      return { text: 'The canary device succeeded — the rest of the group is waiting.', variant: 'success' }
    }
    return { text: `All ${batch.total} devices succeeded.`, variant: 'success' }
  }
  if (batch.stage === 'CANARY') {
    return { text: 'The canary device failed — the rollout stopped before any other device.', variant: 'danger' }
  }
  return {
    text: `${succeeded} of ${batch.total} devices succeeded, ${failed} did not.`,
    variant: 'danger',
  }
}

export default function BatchDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { data: batch, isLoading, isError, refetch } = useBatch(id ?? '')
  const retryBatch = useRetryBatch()
  const continueRollout = useContinueRollout()

  if (isError) {
    return (
      <Alert variant="danger">
        This rollout could not be loaded — it may have been deleted.{' '}
        <Button size="sm" variant="outline-danger" onClick={() => refetch()}>
          Try again
        </Button>{' '}
        <Link to="/jobs">Back to jobs</Link>
      </Alert>
    )
  }

  if (isLoading || !batch) {
    return <Spinner role="status" aria-label="Loading rollout" />
  }

  const failed = count(batch, ['FAILED', 'TIMED_OUT', 'CANCELLED'])
  const summary = rollup(batch)

  return (
    <>
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <Link to="/groups">Groups</Link>
          </li>
          {batch.groupId != null && (
            <li className="breadcrumb-item">
              <Link to={`/groups/${batch.groupId}`}>{batch.groupName}</Link>
            </li>
          )}
          <li className="breadcrumb-item active" aria-current="page">
            Rollout #{batch.id.slice(0, 8)}
          </li>
        </ol>
      </nav>

      <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
        <h1 className="h3 mb-0">Rollout #{batch.id.slice(0, 8)}</h1>
        {batch.stage === 'CANARY' && (
          <Badge bg="warning" text="dark" title="Applied to one device first">
            Canary
          </Badge>
        )}
        {batch.stage === 'FULL' && (
          <Badge bg="info" text="dark" title="Canary succeeded, rolled out to the whole group">
            Full rollout
          </Badge>
        )}
        {!batch.done && <Spinner size="sm" role="status" aria-label="Rollout in progress" />}
        <span className="text-secondary" title={absoluteTime(batch.createdAt)}>
          started {relativeTime(batch.createdAt)}
          {batch.triggeredBy ? ` by ${batch.triggeredBy}` : ''}
        </span>
        {batch.done && failed > 0 && (
          <Button
            size="sm"
            className="ms-auto"
            disabled={retryBatch.isPending}
            onClick={() =>
              retryBatch.mutate(batch.id, { onSuccess: (created) => navigate(`/batches/${created.id}`) })
            }
          >
            {retryBatch.isPending ? 'Queuing…' : `Retry ${failed} failed ${failed === 1 ? 'device' : 'devices'}`}
          </Button>
        )}
        {batch.stage === 'CANARY' && batch.done && failed === 0 && (
          <Button
            size="sm"
            className="ms-auto"
            disabled={continueRollout.isPending}
            onClick={() => continueRollout.mutate(batch.id, { onSuccess: () => refetch() })}
          >
            {continueRollout.isPending ? 'Queuing…' : 'Continue rollout to the rest of the group'}
          </Button>
        )}
      </div>

      <Alert variant={summary.variant} role="status">
        {summary.text}
      </Alert>

      {batch.total === 0 ? (
        <Alert variant="secondary">
          This rollout contains no jobs — the group had no devices when it was started.{' '}
          {batch.groupId != null && <Link to={`/groups/${batch.groupId}`}>Back to the group</Link>}
        </Alert>
      ) : (
        <JobsTable jobs={batch.jobs} />
      )}
      {!batch.done && (
        <p className="text-secondary small mb-0">
          <Badge bg="secondary" className="me-1">
            Live
          </Badge>
          This page updates automatically until every device finished.
        </p>
      )}
    </>
  )
}
