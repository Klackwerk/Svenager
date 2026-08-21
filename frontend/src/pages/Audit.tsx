import { useEffect, useMemo, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import Spinner from 'react-bootstrap/Spinner'
import Table from 'react-bootstrap/Table'
import { useAudit } from '../api/hooks'
import TableToolbar from '../components/TableToolbar'
import { absoluteTime, relativeTime } from '../lib/time'

const PAGE_SIZE = 50

/** Reads as danger for failures/deletions, neutral otherwise. */
function actionVariant(action: string): string {
  if (action.includes('failed') || action.includes('denied')) return 'danger'
  if (action.includes('deleted') || action.includes('revoked') || action.includes('removed')) return 'warning'
  return 'secondary'
}

export default function Audit() {
  const [offset, setOffset] = useState(0)
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => setQ(search.trim()), 300)
    return () => clearTimeout(timer)
  }, [search])

  const filters = useMemo(
    () => ({ q: q || undefined, from: from || undefined, to: to || undefined }),
    [q, from, to],
  )
  useEffect(() => setOffset(0), [filters])

  const { data: page, isLoading, isError } = useAudit(filters, offset, PAGE_SIZE)
  const entries = page?.items ?? []
  const total = page?.total ?? 0
  const lastPageOffset = Math.floor(Math.max(total - 1, 0) / PAGE_SIZE) * PAGE_SIZE
  const filtersActive = q !== '' || from !== '' || to !== ''

  const clearFilters = () => {
    setSearch('')
    setQ('')
    setFrom('')
    setTo('')
  }

  return (
    <>
      <h1 className="h3 mb-3">Audit log</h1>
      <p className="text-secondary">
        Who did what, when and from where. Sign-ins, user and device changes, group and variable edits,
        repository and enrollment actions — secret values are never recorded.
      </p>

      {isLoading && <Spinner role="status" aria-label="Loading audit log" />}
      {isError && <Alert variant="danger">The audit log could not be loaded. Retrying automatically…</Alert>}

      {page && (
        <TableToolbar
          search={search}
          onSearchChange={setSearch}
          searchPlaceholder="Search actor, action or summary…"
          shown={entries.length}
          total={total}
          noun="entries"
          filtersActive={filtersActive}
          onClear={clearFilters}
        >
          <Form.Control
            type="date"
            aria-label="From date"
            title="From date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            style={{ maxWidth: '10.5rem' }}
          />
          <Form.Control
            type="date"
            aria-label="Until date"
            title="Until date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            style={{ maxWidth: '10.5rem' }}
          />
        </TableToolbar>
      )}

      {page && total === 0 && (
        <Alert variant="secondary">
          {filtersActive ? (
            <>
              No entries match the current filter.{' '}
              <Alert.Link as="button" onClick={clearFilters}>
                Clear filters
              </Alert.Link>
            </>
          ) : (
            'Nothing recorded yet.'
          )}
        </Alert>
      )}

      {entries.length > 0 && (
        <Table responsive hover className="align-middle">
          <thead>
            <tr>
              <th>When</th>
              <th>Actor</th>
              <th>Action</th>
              <th>Summary</th>
              <th>IP</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id}>
                <td title={absoluteTime(entry.at)} className="text-nowrap">
                  {relativeTime(entry.at)}
                </td>
                <td className="fw-medium">{entry.actor}</td>
                <td>
                  <Badge
                    bg={actionVariant(entry.action)}
                    text={actionVariant(entry.action) === 'warning' ? 'dark' : undefined}
                  >
                    {entry.action}
                  </Badge>
                </td>
                <td style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{entry.summary ?? '—'}</td>
                <td>{entry.ip ? <code>{entry.ip}</code> : '—'}</td>
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
            {offset + 1}–{Math.min(offset + entries.length, total)} of {total}
          </span>
        </div>
      )}
    </>
  )
}
