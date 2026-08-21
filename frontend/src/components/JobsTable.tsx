import Table from 'react-bootstrap/Table'
import { Link } from 'react-router-dom'
import type { JobSummary } from '../api/types'
import { absoluteTime, relativeTime } from '../lib/time'
import { timeValue, useSort } from '../lib/useSort'
import JobStatusBadge from './JobStatusBadge'
import SortHeader from './SortHeader'

const TYPE_LABELS: Record<string, string> = {
  APPLY_CONFIG: 'Apply configuration',
  CHECK_CONFIG: 'Preview (check mode)',
  AGENT_UPDATE: 'Agent update',
  OPEN_TUNNEL: 'Remote view',
  PING: 'Ping',
}

export default function JobsTable({ jobs, showDevice = true }: { jobs: JobSummary[]; showDevice?: boolean }) {
  const sort = useSort(
    jobs,
    {
      status: (j) => j.status,
      type: (j) => TYPE_LABELS[j.type] ?? j.type,
      device: (j) => j.hostname,
      queued: (j) => timeValue(j.queuedAt),
      finished: (j) => timeValue(j.finishedAt),
      triggeredBy: (j) => j.triggeredBy,
    },
    'queued',
    'desc',
  )

  return (
    <Table responsive hover className="align-middle">
      <thead>
        <tr>
          <SortHeader label="Status" sortKey="status" sort={sort} />
          <SortHeader label="Type" sortKey="type" sort={sort} />
          {showDevice && <SortHeader label="Device" sortKey="device" sort={sort} />}
          <SortHeader label="Queued" sortKey="queued" sort={sort} />
          <SortHeader label="Finished" sortKey="finished" sort={sort} />
          <SortHeader label="Triggered by" sortKey="triggeredBy" sort={sort} />
          <th aria-label="Details" />
        </tr>
      </thead>
      <tbody>
        {sort.sorted.map((job) => (
          <tr key={job.id}>
            <td>
              <JobStatusBadge status={job.status} />
            </td>
            <td>{TYPE_LABELS[job.type] ?? job.type}</td>
            {showDevice && (
              <td>
                <Link to={`/devices/${job.deviceId}`} className="text-decoration-none">
                  {job.hostname}
                </Link>
              </td>
            )}
            <td title={absoluteTime(job.queuedAt)}>{relativeTime(job.queuedAt)}</td>
            <td title={absoluteTime(job.finishedAt)}>{relativeTime(job.finishedAt)}</td>
            <td>{job.triggeredBy ?? '—'}</td>
            <td className="text-end">
              <Link to={`/jobs/${job.id}`}>Details</Link>
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  )
}
