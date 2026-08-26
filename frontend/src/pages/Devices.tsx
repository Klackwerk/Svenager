import { useEffect, useMemo, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import Spinner from 'react-bootstrap/Spinner'
import Table from 'react-bootstrap/Table'
import { Link } from 'react-router-dom'
import { useOpenJob } from '../lib/jobLink'
import {
  useApplyDevice,
  useBulkAddToGroup,
  useBulkApply,
  useBulkRemoveFromGroup,
  useBulkSetStatus,
  useDeleteDevice,
  useDevices,
  useGroups,
} from '../api/hooks'
import type { DeviceSummary } from '../api/types'
import MultiSelectFilter from '../components/MultiSelectFilter'
import SortHeader from '../components/SortHeader'
import TableToolbar from '../components/TableToolbar'
import { useToast } from '../components/ToastProvider'
import { absoluteTime, relativeTime } from '../lib/time'
import type { SortDirection } from '../lib/useSort'

const PAGE_SIZE = 50

export default function Devices() {
  const openJob = useOpenJob()
  const toast = useToast()
  const deleteDevice = useDeleteDevice()
  const applyDevice = useApplyDevice()
  const bulkAddToGroup = useBulkAddToGroup()
  const bulkRemoveFromGroup = useBulkRemoveFromGroup()
  const bulkApply = useBulkApply()
  const bulkSetStatus = useBulkSetStatus()
  const { data: groups } = useGroups()

  const [toDelete, setToDelete] = useState<DeviceSummary | null>(null)
  const [confirmBulkDisable, setConfirmBulkDisable] = useState(false)
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [statuses, setStatuses] = useState<string[]>([])
  const [groupFilter, setGroupFilter] = useState<string[]>([])
  const [offset, setOffset] = useState(0)
  const [sortKey, setSortKey] = useState('hostname')
  const [sortDir, setSortDir] = useState<SortDirection>('asc')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkGroup, setBulkGroup] = useState('')

  // Debounce typing into the server-side search parameter.
  useEffect(() => {
    const timer = setTimeout(() => setQ(search.trim()), 300)
    return () => clearTimeout(timer)
  }, [search])

  const filters = useMemo(
    () => ({
      q: q || undefined,
      statuses: statuses.length ? statuses : undefined,
      groupIds: groupFilter.length ? groupFilter : undefined,
    }),
    [q, statuses, groupFilter],
  )

  // A changed filter always starts back at the first page.
  useEffect(() => setOffset(0), [filters])

  const { data: page, isLoading, isError } = useDevices(filters, offset, PAGE_SIZE, sortKey, sortDir)

  const devices = page?.items ?? []
  const total = page?.total ?? 0
  const lastPageOffset = Math.floor(Math.max(total - 1, 0) / PAGE_SIZE) * PAGE_SIZE
  const filtersActive = q !== '' || statuses.length > 0 || groupFilter.length > 0

  const sort = {
    key: sortKey,
    direction: sortDir,
    toggle: (next: string) => {
      if (sortKey === next) {
        setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
      } else {
        setSortKey(next)
        setSortDir('asc')
      }
    },
  }

  const clearFilters = () => {
    setSearch('')
    setQ('')
    setStatuses([])
    setGroupFilter([])
  }

  const toggleSelected = (id: string, checked: boolean) => {
    setSelected((current) => {
      const next = new Set(current)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })
  }

  const pageIds = devices.map((d) => d.id)
  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const togglePage = (checked: boolean) => {
    setSelected((current) => {
      const next = new Set(current)
      pageIds.forEach((id) => (checked ? next.add(id) : next.delete(id)))
      return next
    })
  }

  const selectedIds = [...selected]
  const bulkGroupName = (groups ?? []).find((g) => g.id === bulkGroup)?.name ?? ''
  const bulkPending =
    bulkAddToGroup.isPending || bulkRemoveFromGroup.isPending || bulkApply.isPending || bulkSetStatus.isPending
  const plural = (n: number) => (n === 1 ? 'device' : 'devices')

  const confirmDelete = () => {
    if (!toDelete) return
    deleteDevice.mutate(toDelete.id, { onSettled: () => setToDelete(null) })
  }

  return (
    <>
      <div className="d-flex align-items-baseline gap-2 mb-3">
        <h1 className="h3 mb-0">Devices</h1>
        {page && (
          <span className="text-secondary">
            {page.online} of {page.all} online
          </span>
        )}
      </div>

      {isLoading && <Spinner role="status" aria-label="Loading devices" />}
      {isError && <Alert variant="danger">Devices could not be loaded. Retrying automatically…</Alert>}

      {page && page.all === 0 && (
        <Alert variant="secondary">
          No devices yet. Create an enrollment token on the <Link to="/enrollment">Enrollment</Link> page and run the
          shown command on a device to register it.
        </Alert>
      )}

      {page && page.all > 0 && (
        <TableToolbar
          search={search}
          onSearchChange={setSearch}
          searchPlaceholder="Search hostname, agent version or ID…"
          shown={devices.length}
          total={total}
          noun="devices"
          filtersActive={filtersActive}
          onClear={clearFilters}
        >
          <MultiSelectFilter
            label="Status"
            options={[
              { value: 'online', label: 'Online' },
              { value: 'offline', label: 'Offline' },
              { value: 'disabled', label: 'Disabled' },
            ]}
            selected={statuses}
            onChange={setStatuses}
          />
          <MultiSelectFilter
            label="Groups"
            options={[
              { value: 'none', label: 'Not in any group' },
              ...(groups ?? []).map((g) => ({ value: g.id, label: g.name })),
            ]}
            selected={groupFilter}
            onChange={setGroupFilter}
          />
        </TableToolbar>
      )}

      {selected.size > 0 && (
        <div className="d-flex flex-wrap align-items-center gap-2 mb-3 p-2 border rounded bg-body-tertiary">
          <span className="fw-medium">{selected.size} selected</span>
          <Form.Select
            size="sm"
            aria-label="Group for bulk action"
            value={bulkGroup}
            onChange={(e) => setBulkGroup(e.target.value)}
            style={{ maxWidth: '13rem' }}
          >
            <option value="">Choose a group…</option>
            {(groups ?? []).map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </Form.Select>
          <Button
            size="sm"
            variant="outline-primary"
            disabled={!bulkGroup || bulkPending}
            onClick={() =>
              bulkAddToGroup.mutate(
                { groupId: bulkGroup, deviceIds: selectedIds },
                {
                  onSuccess: () =>
                    toast({
                      variant: 'success',
                      text: `Added ${selectedIds.length} ${plural(selectedIds.length)} to ${bulkGroupName}.`,
                    }),
                },
              )
            }
          >
            Add to group
          </Button>
          <Button
            size="sm"
            variant="outline-primary"
            disabled={!bulkGroup || bulkPending}
            onClick={() =>
              bulkRemoveFromGroup.mutate(
                { groupId: bulkGroup, deviceIds: selectedIds },
                {
                  onSuccess: () =>
                    toast({
                      variant: 'success',
                      text: `Removed ${selectedIds.length} ${plural(selectedIds.length)} from ${bulkGroupName}.`,
                    }),
                },
              )
            }
          >
            Remove from group
          </Button>
          <Button
            size="sm"
            variant="outline-primary"
            disabled={bulkPending}
            onClick={() =>
              bulkApply.mutate(selectedIds, {
                onSuccess: () =>
                  toast({
                    variant: 'success',
                    text: `Queued configuration applies for ${selectedIds.length} ${plural(selectedIds.length)}.`,
                  }),
              })
            }
          >
            Apply configuration
          </Button>
          <Button
            size="sm"
            variant="outline-danger"
            disabled={bulkPending}
            onClick={() => setConfirmBulkDisable(true)}
          >
            Disable
          </Button>
          <Button size="sm" variant="link" onClick={() => setSelected(new Set())}>
            Clear selection
          </Button>
        </div>
      )}

      {page && page.all > 0 && total === 0 && (
        <Alert variant="secondary">
          No devices match the current filter.{' '}
          <Alert.Link as="button" onClick={clearFilters}>
            Clear filters
          </Alert.Link>
        </Alert>
      )}

      {devices.length > 0 && (
        <Table responsive hover className="align-middle">
          <thead>
            <tr>
              <th>
                <Form.Check
                  id="select-page"
                  aria-label="Select all devices on this page"
                  checked={allOnPageSelected}
                  onChange={(e) => togglePage(e.target.checked)}
                />
              </th>
              <th>Status</th>
              <SortHeader label="Hostname" sortKey="hostname" sort={sort} />
              <th>Groups</th>
              <SortHeader label="IP address" sortKey="ip" sort={sort} />
              <SortHeader label="Last contact" sortKey="lastContact" sort={sort} />
              <SortHeader label="Last job" sortKey="lastJob" sort={sort} />
              <SortHeader label="Agent" sortKey="agent" sort={sort} />
              <SortHeader label="Enrolled" sortKey="enrolled" sort={sort} />
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {devices.map((device) => (
              <tr key={device.id}>
                <td>
                  <Form.Check
                    id={`select-${device.id}`}
                    aria-label={`Select ${device.hostname}`}
                    checked={selected.has(device.id)}
                    onChange={(e) => toggleSelected(device.id, e.target.checked)}
                  />
                </td>
                <td>
                  {device.status === 'DISABLED' ? (
                    <Badge bg="secondary">Disabled</Badge>
                  ) : device.online ? (
                    <Badge bg="success">Online</Badge>
                  ) : (
                    <Badge bg="danger">Offline</Badge>
                  )}
                </td>
                <td className="fw-medium">
                  <Link to={`/devices/${device.id}`} className="text-decoration-none">
                    {device.hostname}
                  </Link>
                </td>
                <td>
                  {(device.groups ?? []).length === 0
                    ? '—'
                    : device.groups.map((g, index) => (
                        <span key={g.id}>
                          {index > 0 && ', '}
                          <Link to={`/groups/${g.id}`} className="text-decoration-none">
                            {g.name}
                          </Link>
                        </span>
                      ))}
                </td>
                <td>{device.lastIp ? <code>{device.lastIp}</code> : '—'}</td>
                <td title={absoluteTime(device.lastContactAt)}>{relativeTime(device.lastContactAt)}</td>
                <td title={absoluteTime(device.lastJobAt)}>{relativeTime(device.lastJobAt)}</td>
                <td>{device.agentVersion ?? '—'}</td>
                <td title={absoluteTime(device.enrolledAt)}>{relativeTime(device.enrolledAt)}</td>
                <td className="text-end text-nowrap">
                  <Button
                    size="sm"
                    variant="outline-primary"
                    className="me-2"
                    disabled={applyDevice.isPending && applyDevice.variables?.deviceId === device.id}
                    onClick={() =>
                      applyDevice.mutate(
                        { deviceId: device.id },
                        { onSuccess: (job) => openJob(job.id) },
                      )
                    }
                  >
                    Apply
                  </Button>
                  <Button size="sm" variant="outline-danger" onClick={() => setToDelete(device)}>
                    Remove
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      {total > PAGE_SIZE && (
        <div className="d-flex align-items-center gap-2 mt-2">
          <Button
            size="sm"
            variant="outline-secondary"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(offset - PAGE_SIZE, 0))}
          >
            Previous
          </Button>
          <Button
            size="sm"
            variant="outline-secondary"
            disabled={offset >= lastPageOffset}
            onClick={() => setOffset(Math.min(offset + PAGE_SIZE, lastPageOffset))}
          >
            Next
          </Button>
          <span className="text-secondary small">
            {offset + 1}–{Math.min(offset + devices.length, total)} of {total}
          </span>
        </div>
      )}

      <Modal show={confirmBulkDisable} onHide={() => setConfirmBulkDisable(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Disable {selected.size} {plural(selected.size)}?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          Disabled devices are rejected at their next check-in: no new jobs, no remote view, no status updates.
          They keep running their current configuration and can be enabled again at any time.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setConfirmBulkDisable(false)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={bulkSetStatus.isPending}
            onClick={() =>
              bulkSetStatus.mutate(
                { status: 'DISABLED', deviceIds: selectedIds },
                {
                  onSuccess: () =>
                    toast({
                      variant: 'success',
                      text: `Disabled ${selectedIds.length} ${plural(selectedIds.length)}.`,
                    }),
                  onSettled: () => setConfirmBulkDisable(false),
                },
              )
            }
          >
            {bulkSetStatus.isPending ? 'Disabling…' : 'Disable devices'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={toDelete != null} onHide={() => setToDelete(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Remove device?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{toDelete?.hostname}</strong> will be removed from Svenager and will no longer be able to check in.
          The device itself is not changed. This cannot be undone.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setToDelete(null)}>
            Cancel
          </Button>
          <Button variant="danger" onClick={confirmDelete} disabled={deleteDevice.isPending}>
            {deleteDevice.isPending ? 'Removing…' : 'Remove device'}
          </Button>
        </Modal.Footer>
      </Modal>
    </>
  )
}
