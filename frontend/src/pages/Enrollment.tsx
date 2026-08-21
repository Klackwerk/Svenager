import { useState, type FormEvent } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Col from 'react-bootstrap/Col'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import Row from 'react-bootstrap/Row'
import Spinner from 'react-bootstrap/Spinner'
import Table from 'react-bootstrap/Table'
import {
  useCreateEnrollmentToken,
  useDecideEnrollmentRequest,
  useEnrollmentRequests,
  useEnrollmentTokens,
  useGroups,
  useMe,
  useRevokeEnrollmentToken,
} from '../api/hooks'
import MultiSelectFilter from '../components/MultiSelectFilter'
import type { CreatedEnrollmentToken, EnrollmentRequestInfo, EnrollmentToken } from '../api/types'
import InputGroup from 'react-bootstrap/InputGroup'
import SortHeader from '../components/SortHeader'
import TableToolbar from '../components/TableToolbar'
import { useToast } from '../components/ToastProvider'
import { absoluteTime, relativeTime } from '../lib/time'
import { timeValue, useSort } from '../lib/useSort'

const EXPIRY_OPTIONS = [
  { label: '1 hour', hours: 1 },
  { label: '24 hours', hours: 24 },
  { label: '7 days', hours: 7 * 24 },
  { label: 'Never', hours: null },
]

function tokenState(token: EnrollmentToken): { label: string; bg: string } {
  if (token.revoked) return { label: 'Revoked', bg: 'secondary' }
  if (token.usedCount >= token.maxUses) return { label: 'Used up', bg: 'secondary' }
  if (token.expiresAt && Date.parse(token.expiresAt) < Date.now()) return { label: 'Expired', bg: 'secondary' }
  return { label: 'Active', bg: 'success' }
}

export default function Enrollment() {
  const { data: tokens, isLoading } = useEnrollmentTokens()
  const { data: me } = useMe()
  const { data: requests } = useEnrollmentRequests()
  const decideRequest = useDecideEnrollmentRequest()
  const createToken = useCreateEnrollmentToken()
  const revokeToken = useRevokeEnrollmentToken()
  const isAdmin = me?.roles?.includes('ROLE_ADMIN') ?? false
  const pendingRequests = (requests ?? []).filter((r) => r.status === 'PENDING')

  const { data: groups } = useGroups()
  const [label, setLabel] = useState('')
  const [maxUses, setMaxUses] = useState(1)
  const [expiresInHours, setExpiresInHours] = useState<number | null>(24)
  const [targetGroupIds, setTargetGroupIds] = useState<string[]>([])
  const [created, setCreated] = useState<CreatedEnrollmentToken | null>(null)
  const [copied, setCopied] = useState(false)
  const [tokenCopied, setTokenCopied] = useState(false)
  const toast = useToast()
  const [search, setSearch] = useState('')
  const [stateFilter, setStateFilter] = useState<'all' | 'active' | 'inactive'>('all')
  const [toRevoke, setToRevoke] = useState<EnrollmentToken | null>(null)
  const [toDeny, setToDeny] = useState<EnrollmentRequestInfo | null>(null)

  const filtersActive = search.trim() !== '' || stateFilter !== 'all'
  const query = search.trim().toLowerCase()
  const filteredTokens = (tokens ?? []).filter((token) => {
    const active = tokenState(token).label === 'Active'
    const matchesText =
      !query || token.label.toLowerCase().includes(query) || (token.createdBy ?? '').toLowerCase().includes(query)
    const matchesState =
      stateFilter === 'all' || (stateFilter === 'active' ? active : !active)
    return matchesText && matchesState
  })

  const clearFilters = () => {
    setSearch('')
    setStateFilter('all')
  }

  const sort = useSort(
    filteredTokens,
    {
      state: (t) => tokenState(t).label,
      label: (t) => t.label,
      used: (t) => t.usedCount / Math.max(1, t.maxUses),
      expires: (t) => timeValue(t.expiresAt),
      created: (t) => timeValue(t.createdAt),
    },
    'created',
    'desc',
  )

  const submit = (event: FormEvent) => {
    event.preventDefault()
    createToken.mutate(
      { label: label.trim(), maxUses, expiresInHours, targetGroupIds },
      {
        onSuccess: (token) => {
          setCreated(token)
          setCopied(false)
          setTokenCopied(false)
          setLabel('')
          setTargetGroupIds([])
        },
      },
    )
  }

  const enrollCommand = created?.installCommand ?? ''

  const copy = async (text: string, done: (v: boolean) => void) => {
    try {
      await navigator.clipboard.writeText(text)
      done(true)
    } catch {
      toast({ variant: 'danger', text: 'Copying failed — select the text and copy it manually.' })
    }
  }

  return (
    <>
      <h1 className="h3 mb-3">Enrollment</h1>

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">New enrollment token</Card.Title>
          <Card.Text className="text-secondary">
            An enrollment token lets new devices register themselves. Its value is shown only once, right after
            creation.
          </Card.Text>
          {createToken.isError && <Alert variant="danger">The token could not be created. Please try again.</Alert>}
          <Form onSubmit={submit}>
            <Row className="g-3 align-items-end">
              <Col xs={12} md={3}>
                <Form.Group controlId="token-label">
                  <Form.Label>Label</Form.Label>
                  <Form.Control
                    placeholder="e.g. Self-service terminals batch 3"
                    value={label}
                    onChange={(e) => setLabel(e.target.value)}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={6} md={2}>
                <Form.Group controlId="token-uses">
                  <Form.Label>Devices</Form.Label>
                  <Form.Control
                    type="number"
                    min={1}
                    value={maxUses}
                    onChange={(e) => setMaxUses(Math.max(1, Number(e.target.value)))}
                  />
                </Form.Group>
              </Col>
              <Col xs={6} md={2}>
                <Form.Group controlId="token-expiry">
                  <Form.Label>Valid for</Form.Label>
                  <Form.Select
                    value={String(expiresInHours)}
                    onChange={(e) => setExpiresInHours(e.target.value === 'null' ? null : Number(e.target.value))}
                  >
                    {EXPIRY_OPTIONS.map((option) => (
                      <option key={option.label} value={String(option.hours)}>
                        {option.label}
                      </option>
                    ))}
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col xs={12} md={3}>
                <Form.Group>
                  <Form.Label className="d-block" htmlFor="token-target-groups">
                    Joins groups
                  </Form.Label>
                  <MultiSelectFilter
                    label="Groups"
                    options={(groups ?? []).map((g) => ({ value: g.id, label: g.name }))}
                    selected={targetGroupIds}
                    onChange={setTargetGroupIds}
                  />
                </Form.Group>
              </Col>
              <Col xs={12} md={2}>
                <Button type="submit" className="w-100" disabled={createToken.isPending}>
                  {createToken.isPending ? 'Creating…' : 'Create'}
                </Button>
              </Col>
            </Row>
          </Form>
        </Card.Body>
      </Card>

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">
            Enrollment requests{' '}
            {pendingRequests.length > 0 && <Badge bg="warning" text="dark">{pendingRequests.length} pending</Badge>}
          </Card.Title>
          <Card.Text className="text-secondary small">
            Pre-configured devices (e.g. cloned Raspberry Pi images running{' '}
            <code>svenager-agent run --server …</code>) ask to join here without a token.
            {isAdmin ? ' Approve a device and it enrolls itself within half a minute.' : ' Only administrators can approve them.'}
          </Card.Text>
          {(requests ?? []).length === 0 ? (
            <p className="text-secondary mb-0">No devices are asking to join right now.</p>
          ) : (
            <Table responsive size="sm" hover className="align-middle mb-0">
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Hostname</th>
                  <th>Machine ID</th>
                  <th>Last seen</th>
                  <th aria-label="Decision" />
                </tr>
              </thead>
              <tbody>
                {requests!.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <Badge
                        bg={r.status === 'PENDING' ? 'warning' : r.status === 'DENIED' ? 'secondary' : 'success'}
                        text={r.status === 'PENDING' ? 'dark' : undefined}
                      >
                        {r.status === 'PENDING' ? 'Pending' : r.status === 'DENIED' ? 'Denied' : 'Enrolled'}
                      </Badge>
                    </td>
                    <td className="fw-medium">{r.hostname ?? '—'}</td>
                    <td>
                      <code>{r.requestId.slice(0, 12)}…</code>
                    </td>
                    <td title={absoluteTime(r.lastSeenAt)}>{relativeTime(r.lastSeenAt)}</td>
                    <td className="text-end">
                      {r.status === 'PENDING' && (
                        <span className="d-inline-flex gap-2">
                          <Button
                            size="sm"
                            variant="success"
                            disabled={!isAdmin || decideRequest.isPending}
                            title={isAdmin ? undefined : 'Requires administrator rights'}
                            onClick={() => decideRequest.mutate({ id: r.id, decision: 'approve' })}
                          >
                            Approve
                          </Button>
                          <Button
                            size="sm"
                            variant="outline-danger"
                            disabled={!isAdmin || decideRequest.isPending}
                            title={isAdmin ? undefined : 'Requires administrator rights'}
                            onClick={() => setToDeny(r)}
                          >
                            Deny
                          </Button>
                        </span>
                      )}
                      {r.status !== 'PENDING' && r.decidedBy && (
                        <span className="text-secondary small">by {r.decidedBy}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card.Body>
      </Card>

      <h2 className="h5 mb-3">Existing tokens</h2>
      {isLoading && <Spinner role="status" aria-label="Loading tokens" />}
      {tokens && tokens.length === 0 && <Alert variant="secondary">No enrollment tokens yet.</Alert>}
      {tokens && tokens.length > 0 && (
        <TableToolbar
          search={search}
          onSearchChange={setSearch}
          searchPlaceholder="Search label or creator…"
          shown={filteredTokens.length}
          total={tokens.length}
          noun="tokens"
          filtersActive={filtersActive}
          onClear={clearFilters}
        >
          <Form.Select
            aria-label="Filter by state"
            value={stateFilter}
            onChange={(e) => setStateFilter(e.target.value as 'all' | 'active' | 'inactive')}
            style={{ maxWidth: '11rem' }}
          >
            <option value="all">All states</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </Form.Select>
        </TableToolbar>
      )}
      {tokens && tokens.length > 0 && filteredTokens.length === 0 && (
        <Alert variant="secondary">
          No tokens match the current filter.{' '}
          <Alert.Link as="button" onClick={clearFilters}>
            Clear filters
          </Alert.Link>
        </Alert>
      )}
      {filteredTokens.length > 0 && (
        <Table responsive hover className="align-middle">
          <thead>
            <tr>
              <SortHeader label="State" sortKey="state" sort={sort} />
              <SortHeader label="Label" sortKey="label" sort={sort} />
              <th>Joins groups</th>
              <SortHeader label="Used" sortKey="used" sort={sort} />
              <SortHeader label="Expires" sortKey="expires" sort={sort} />
              <SortHeader label="Created" sortKey="created" sort={sort} />
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {sort.sorted.map((token) => {
              const state = tokenState(token)
              return (
                <tr key={token.id}>
                  <td>
                    <Badge bg={state.bg}>{state.label}</Badge>
                  </td>
                  <td className="fw-medium">{token.label}</td>
                  <td className="text-secondary small">
                    {(token.targetGroups ?? []).map((g) => g.name).join(', ') || '—'}
                  </td>
                  <td>
                    {token.usedCount} / {token.maxUses}
                  </td>
                  <td title={absoluteTime(token.expiresAt)}>
                    {token.expiresAt ? relativeTime(token.expiresAt) : 'never'}
                  </td>
                  <td title={absoluteTime(token.createdAt)}>
                    {relativeTime(token.createdAt)}
                    {token.createdBy ? ` by ${token.createdBy}` : ''}
                  </td>
                  <td className="text-end">
                    {!token.revoked && state.label === 'Active' && (
                      <Button size="sm" variant="outline-danger" onClick={() => setToRevoke(token)}>
                        Revoke
                      </Button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </Table>
      )}

      <Modal show={toRevoke != null} onHide={() => setToRevoke(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Revoke token?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{toRevoke?.label}</strong> stops working immediately — devices that did not enroll with it yet
          will not be able to. Already-enrolled devices are not affected. This cannot be undone.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setToRevoke(null)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={revokeToken.isPending}
            onClick={() => toRevoke && revokeToken.mutate(toRevoke.id, { onSettled: () => setToRevoke(null) })}
          >
            {revokeToken.isPending ? 'Revoking…' : 'Revoke token'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={toDeny != null} onHide={() => setToDeny(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Deny enrollment request?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{toDeny?.hostname ?? 'This device'}</strong> will be marked as denied and will not be enrolled.
          The device stays unmanaged until someone enrolls it another way.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setToDeny(null)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={decideRequest.isPending}
            onClick={() =>
              toDeny &&
              decideRequest.mutate({ id: toDeny.id, decision: 'deny' }, { onSettled: () => setToDeny(null) })
            }
          >
            {decideRequest.isPending ? 'Denying…' : 'Deny request'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={created != null} onHide={() => setCreated(null)} centered size="lg">
        <Modal.Header closeButton>
          <Modal.Title>Token created</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <p>
            Run this command on each device you want to enroll. It downloads the agent from this Svenager
            instance, enrolls the device and starts the service — one step, nothing to prepare. The token is
            shown <strong>only this once</strong> — close this dialog only after you saved it.
          </p>
          <pre className="bg-body-tertiary border rounded p-3 text-wrap">
            <code>{enrollCommand}</code>
          </pre>
          <Button variant={copied ? 'success' : 'outline-primary'} onClick={() => copy(enrollCommand, setCopied)}>
            {copied ? 'Copied ✓' : 'Copy command'}
          </Button>
          <Form.Group className="mt-4" controlId="created-token">
            <Form.Label>Just the token (for manual installs)</Form.Label>
            <InputGroup>
              <Form.Control readOnly value={created?.token ?? ''} onFocus={(e) => e.target.select()} />
              <Button
                variant={tokenCopied ? 'success' : 'outline-secondary'}
                onClick={() => copy(created?.token ?? '', setTokenCopied)}
              >
                {tokenCopied ? 'Copied ✓' : 'Copy token'}
              </Button>
            </InputGroup>
          </Form.Group>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="primary" onClick={() => setCreated(null)}>
            I saved the token — close
          </Button>
        </Modal.Footer>
      </Modal>
    </>
  )
}
