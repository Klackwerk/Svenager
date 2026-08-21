import { useEffect, useMemo, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import Spinner from 'react-bootstrap/Spinner'
import { useGroups, useJobs } from '../api/hooks'
import type { DeviceSummary } from '../api/types'
import DeviceAutocomplete from '../components/DeviceAutocomplete'
import JobsTable from '../components/JobsTable'
import TableToolbar from '../components/TableToolbar'

const PAGE_SIZE = 50

const STATUS_GROUPS: Record<string, string[]> = {
  active: ['PENDING', 'DELIVERED', 'RUNNING'],
  succeeded: ['SUCCEEDED'],
  failed: ['FAILED', 'TIMED_OUT'],
  cancelled: ['CANCELLED'],
}

export default function Jobs() {
  const [offset, setOffset] = useState(0)
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('all')
  const [type, setType] = useState('all')
  const [groupId, setGroupId] = useState('')
  const [device, setDevice] = useState<DeviceSummary | null>(null)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  // Debounce typing into the server-side search parameter.
  useEffect(() => {
    const timer = setTimeout(() => setQ(search.trim()), 300)
    return () => clearTimeout(timer)
  }, [search])

  const filters = useMemo(
    () => ({
      q: q || undefined,
      statuses: status === 'all' ? undefined : STATUS_GROUPS[status],
      type: type === 'all' ? undefined : type,
      groupId: groupId || undefined,
      deviceId: device?.id,
      from: from || undefined,
      to: to || undefined,
    }),
    [q, status, type, groupId, device, from, to],
  )

  // A changed filter always starts back at the first page.
  useEffect(() => setOffset(0), [filters])

  const { data: page, isLoading, isError } = useJobs(filters, offset, PAGE_SIZE)
  const { data: groups } = useGroups()

  const jobs = page?.items ?? []
  const total = page?.total ?? 0
  const lastPageOffset = Math.floor(Math.max(total - 1, 0) / PAGE_SIZE) * PAGE_SIZE

  const filtersActive =
    q !== '' ||
    status !== 'all' ||
    type !== 'all' ||
    groupId !== '' ||
    device != null ||
    from !== '' ||
    to !== ''

  const clearFilters = () => {
    setSearch('')
    setQ('')
    setStatus('all')
    setType('all')
    setGroupId('')
    setDevice(null)
    setFrom('')
    setTo('')
  }

  return (
    <>
      <h1 className="h3 mb-3">Jobs</h1>
      {isLoading && <Spinner role="status" aria-label="Loading jobs" />}
      {isError && <Alert variant="danger">Jobs could not be loaded. Retrying automatically…</Alert>}
      {page && total === 0 && !filtersActive && (
        <Alert variant="secondary">
          No jobs yet. Use "Apply configuration" on a device or group to run the assigned roles.
        </Alert>
      )}
      {page && (total > 0 || filtersActive) && (
        <TableToolbar
          search={search}
          onSearchChange={setSearch}
          searchPlaceholder="Search device or triggered by…"
          shown={jobs.length}
          total={total}
          noun="jobs"
          filtersActive={filtersActive}
          onClear={clearFilters}
        >
          <Form.Select
            aria-label="Filter by status"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            style={{ maxWidth: '10rem' }}
          >
            <option value="all">All statuses</option>
            <option value="active">Active</option>
            <option value="succeeded">Succeeded</option>
            <option value="failed">Failed</option>
            <option value="cancelled">Cancelled</option>
          </Form.Select>
          <Form.Select
            aria-label="Filter by type"
            value={type}
            onChange={(e) => setType(e.target.value)}
            style={{ maxWidth: '12rem' }}
          >
            <option value="all">All types</option>
            <option value="APPLY_CONFIG">Apply configuration</option>
            <option value="CHECK_CONFIG">Preview (check mode)</option>
            <option value="AGENT_UPDATE">Agent update</option>
            <option value="OPEN_TUNNEL">Remote view</option>
          </Form.Select>
          <DeviceAutocomplete selected={device} onSelect={setDevice} />
          <Form.Select
            aria-label="Filter by group"
            value={groupId}
            onChange={(e) => setGroupId(e.target.value)}
            style={{ maxWidth: '11rem' }}
          >
            <option value="">All groups</option>
            {(groups ?? []).map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </Form.Select>
          <Form.Control
            type="date"
            aria-label="Queued from"
            title="Queued from"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            style={{ maxWidth: '10.5rem' }}
          />
          <Form.Control
            type="date"
            aria-label="Queued until"
            title="Queued until"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            style={{ maxWidth: '10.5rem' }}
          />
        </TableToolbar>
      )}
      {page && total === 0 && filtersActive && (
        <Alert variant="secondary">
          No jobs match the current filter.{' '}
          <Alert.Link as="button" onClick={clearFilters}>
            Clear filters
          </Alert.Link>
        </Alert>
      )}
      {jobs.length > 0 && <JobsTable jobs={jobs} />}
      {total > PAGE_SIZE && (
        <div className="d-flex align-items-center gap-2 mt-2">
          <Button
            size="sm"
            variant="outline-secondary"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(offset - PAGE_SIZE, 0))}
          >
            Newer
          </Button>
          <Button
            size="sm"
            variant="outline-secondary"
            disabled={offset >= lastPageOffset}
            onClick={() => setOffset(Math.min(offset + PAGE_SIZE, lastPageOffset))}
          >
            Older
          </Button>
          <span className="text-secondary small">
            {offset + 1}–{Math.min(offset + jobs.length, total)} of {total}
          </span>
        </div>
      )}
    </>
  )
}
