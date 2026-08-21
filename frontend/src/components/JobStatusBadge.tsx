import Badge from 'react-bootstrap/Badge'
import type { JobSummary } from '../api/types'

const STYLES: Record<JobSummary['status'], { label: string; bg: string; text?: string }> = {
  PENDING: { label: 'Waiting for device', bg: 'secondary' },
  DELIVERED: { label: 'Sent to device', bg: 'info' },
  RUNNING: { label: 'Running…', bg: 'primary' },
  SUCCEEDED: { label: 'Succeeded', bg: 'success' },
  FAILED: { label: 'Failed', bg: 'danger' },
  TIMED_OUT: { label: 'Timed out', bg: 'warning', text: 'dark' },
  CANCELLED: { label: 'Cancelled', bg: 'secondary' },
}

export default function JobStatusBadge({ status }: { status: JobSummary['status'] }) {
  const style = STYLES[status]
  return (
    <Badge bg={style.bg} text={style.text}>
      {style.label}
    </Badge>
  )
}
