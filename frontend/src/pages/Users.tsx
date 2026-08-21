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
  useCreateSsoMapping,
  useCreateUser,
  useDeleteSsoMapping,
  useGroups,
  useMe,
  useSsoMappings,
  useUpdateUser,
  useUsers,
} from '../api/hooks'
import type { SsoMappingInfo, UserInfo } from '../api/types'

const ROLES: UserInfo['role'][] = ['ADMIN', 'OPERATOR', 'VIEWER']

const ROLE_HELP: Record<UserInfo['role'], string> = {
  ADMIN: 'everything, incl. users and enrollment tokens',
  OPERATOR: 'manage devices, groups, jobs and remote sessions',
  VIEWER: 'read-only access to dashboards and history',
}

function friendlyUserError(error: unknown): string {
  const raw = error instanceof Error ? error.message : ''
  const known: Record<string, string> = {
    'username is required': 'Please enter a username.',
    'username is already taken': 'This username is already taken.',
    'password must be at least 8 characters': 'The password must be at least 8 characters long.',
  }
  return known[raw] ?? 'The user could not be created. Please try again.'
}

export default function Users() {
  const { data: me } = useMe()
  const { data: users, isLoading, isError } = useUsers()
  const createUser = useCreateUser()
  const updateUser = useUpdateUser()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<UserInfo['role']>('VIEWER')
  const [passwordTarget, setPasswordTarget] = useState<UserInfo | null>(null)
  const [newPassword, setNewPassword] = useState('')
  const [toDisable, setToDisable] = useState<UserInfo | null>(null)

  const { data: groups } = useGroups()
  const { data: mappings } = useSsoMappings()
  const createMapping = useCreateSsoMapping()
  const deleteMapping = useDeleteSsoMapping()
  const [idpGroup, setIdpGroup] = useState('')
  const [mappedRole, setMappedRole] = useState('')
  const [mappedGroupId, setMappedGroupId] = useState('')
  const [mappingToDelete, setMappingToDelete] = useState<SsoMappingInfo | null>(null)

  const submitMapping = (event: FormEvent) => {
    event.preventDefault()
    createMapping.mutate(
      {
        idpGroup: idpGroup.trim(),
        role: mappedRole || undefined,
        deviceGroupId: mappedGroupId || undefined,
      },
      {
        onSuccess: () => {
          setIdpGroup('')
          setMappedRole('')
          setMappedGroupId('')
        },
      },
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    createUser.mutate(
      { username: username.trim(), password, role },
      {
        onSuccess: () => {
          setUsername('')
          setPassword('')
          setRole('VIEWER')
        },
      },
    )
  }

  const submitPassword = (event: FormEvent) => {
    event.preventDefault()
    if (!passwordTarget) return
    updateUser.mutate(
      { id: passwordTarget.id, password: newPassword },
      {
        onSuccess: () => {
          setPasswordTarget(null)
          setNewPassword('')
        },
      },
    )
  }

  return (
    <>
      <h1 className="h3 mb-3">Users</h1>
      <p className="text-secondary">
        Who can sign in to Svenager and what they may do. Users are disabled rather than deleted, so their name stays
        on everything they triggered.
      </p>

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">New user</Card.Title>
          {createUser.isError && <Alert variant="danger">{friendlyUserError(createUser.error)}</Alert>}
          <Form onSubmit={submit}>
            <Row className="g-3 align-items-end">
              <Col xs={12} md={3}>
                <Form.Group controlId="user-name">
                  <Form.Label>Username</Form.Label>
                  <Form.Control value={username} onChange={(e) => setUsername(e.target.value)} required />
                </Form.Group>
              </Col>
              <Col xs={12} md={3}>
                <Form.Group controlId="user-password">
                  <Form.Label>Password</Form.Label>
                  <Form.Control
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    minLength={8}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={12} md={4}>
                <Form.Group controlId="user-role">
                  <Form.Label>Role</Form.Label>
                  <Form.Select value={role} onChange={(e) => setRole(e.target.value as UserInfo['role'])}>
                    {ROLES.map((r) => (
                      <option key={r} value={r}>
                        {r} — {ROLE_HELP[r]}
                      </option>
                    ))}
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col xs={12} md={2}>
                <Button type="submit" className="w-100" disabled={createUser.isPending}>
                  {createUser.isPending ? 'Creating…' : 'Create'}
                </Button>
              </Col>
            </Row>
          </Form>
        </Card.Body>
      </Card>

      {isLoading && <Spinner role="status" aria-label="Loading users" />}
      {isError && <Alert variant="danger">Users could not be loaded. Retrying automatically…</Alert>}

      {users && (
        <Table hover responsive>
          <thead>
            <tr>
              <th>Username</th>
              <th>Role</th>
              <th>Access</th>
              <th>Status</th>
              <th>Created</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr>
                <td colSpan={6} className="text-secondary">
                  No users yet — create the first one above.
                </td>
              </tr>
            )}
            {users.map((user) => {
              const self = user.username === me?.username
              return (
                <tr key={user.id} className={user.enabled ? undefined : 'opacity-50'}>
                  <td>
                    {user.username} {self && <Badge bg="secondary">you</Badge>}{' '}
                    {user.source === 'OIDC' && (
                      <Badge bg="info" text="dark" title="Managed by the identity provider">
                        SSO
                      </Badge>
                    )}
                  </td>
                  <td style={{ maxWidth: '12rem' }}>
                    <Form.Select
                      size="sm"
                      value={user.role}
                      disabled={
                        self ||
                        user.source === 'OIDC' ||
                        (updateUser.isPending && updateUser.variables?.id === user.id)
                      }
                      title={
                        self
                          ? 'You cannot change your own role'
                          : user.source === 'OIDC'
                            ? 'The role comes from the identity provider (group mappings)'
                            : undefined
                      }
                      aria-label={`Role of ${user.username}`}
                      onChange={(e) => updateUser.mutate({ id: user.id, role: e.target.value as UserInfo['role'] })}
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </Form.Select>
                  </td>
                  <td className="text-secondary small">
                    {user.allGroups ? 'All groups' : user.scopes.join(', ') || 'No groups'}
                  </td>
                  <td>
                    <Form.Check
                      type="switch"
                      id={`enabled-${user.id}`}
                      label={user.enabled ? 'Enabled' : 'Disabled'}
                      checked={user.enabled}
                      disabled={self || (updateUser.isPending && updateUser.variables?.id === user.id)}
                      title={self ? 'You cannot disable yourself' : undefined}
                      onChange={(e) =>
                        e.target.checked
                          ? updateUser.mutate({ id: user.id, enabled: true })
                          : setToDisable(user)
                      }
                    />
                  </td>
                  <td className="text-secondary small">
                    {user.createdAt ? new Date(user.createdAt).toLocaleDateString() : '—'}
                  </td>
                  <td className="text-end">
                    {user.source === 'LOCAL' && (
                      <Button size="sm" variant="outline-secondary" onClick={() => setPasswordTarget(user)}>
                        Set password
                      </Button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </Table>
      )}

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">SSO group mappings</Card.Title>
          <Card.Text className="text-secondary small">
            What an identity-provider group grants at sign-in: a Svenager role, access to a device group, or
            both. A user's role is the highest of all matching mappings; a non-admin with device-group mappings
            sees only those groups. Changes apply at the next sign-in.
          </Card.Text>
          {createMapping.isError && (
            <Alert variant="danger">
              {(createMapping.error as Error).message || 'The mapping could not be created.'}
            </Alert>
          )}
          <Form onSubmit={submitMapping} className="mb-3">
            <Row className="g-3 align-items-end">
              <Col xs={12} md={4}>
                <Form.Group controlId="mapping-idp-group">
                  <Form.Label>IdP group</Form.Label>
                  <Form.Control
                    value={idpGroup}
                    placeholder="e.g. svenager-admins"
                    onChange={(e) => setIdpGroup(e.target.value)}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={6} md={3}>
                <Form.Group controlId="mapping-role">
                  <Form.Label>Grants role</Form.Label>
                  <Form.Select value={mappedRole} onChange={(e) => setMappedRole(e.target.value)}>
                    <option value="">— none —</option>
                    {ROLES.map((r) => (
                      <option key={r} value={r}>
                        {r}
                      </option>
                    ))}
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col xs={6} md={3}>
                <Form.Group controlId="mapping-device-group">
                  <Form.Label>Grants device group</Form.Label>
                  <Form.Select value={mappedGroupId} onChange={(e) => setMappedGroupId(e.target.value)}>
                    <option value="">— none —</option>
                    {(groups ?? []).map((g) => (
                      <option key={g.id} value={g.id}>
                        {g.name}
                      </option>
                    ))}
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col xs={12} md={2}>
                <Button
                  type="submit"
                  className="w-100"
                  disabled={createMapping.isPending || (!mappedRole && !mappedGroupId)}
                  title={!mappedRole && !mappedGroupId ? 'Pick a role, a device group or both' : undefined}
                >
                  {createMapping.isPending ? 'Adding…' : 'Add mapping'}
                </Button>
              </Col>
            </Row>
          </Form>
          {(mappings ?? []).length === 0 ? (
            <p className="text-secondary mb-0">
              No mappings yet. Tip: map your IdP admin group to the ADMIN role so administrators are recognized
              automatically.
            </p>
          ) : (
            <Table size="sm" responsive className="align-middle mb-0">
              <thead>
                <tr>
                  <th>IdP group</th>
                  <th>Role</th>
                  <th>Device group</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {mappings!.map((mapping) => (
                  <tr key={mapping.id}>
                    <td>
                      <code>{mapping.idpGroup}</code>
                    </td>
                    <td>{mapping.role ?? '—'}</td>
                    <td>{mapping.deviceGroupName ?? '—'}</td>
                    <td className="text-end">
                      <Button size="sm" variant="outline-danger" onClick={() => setMappingToDelete(mapping)}>
                        Remove
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card.Body>
      </Card>

      <Modal show={mappingToDelete != null} onHide={() => setMappingToDelete(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Remove mapping?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          Members of <strong>{mappingToDelete?.idpGroup}</strong> lose{' '}
          {mappingToDelete?.role ? `the ${mappingToDelete.role} role` : ''}
          {mappingToDelete?.role && mappingToDelete?.deviceGroupName ? ' and ' : ''}
          {mappingToDelete?.deviceGroupName ? `access to '${mappingToDelete.deviceGroupName}'` : ''} at their
          next sign-in.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setMappingToDelete(null)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={deleteMapping.isPending}
            onClick={() =>
              mappingToDelete &&
              deleteMapping.mutate(mappingToDelete.id, { onSettled: () => setMappingToDelete(null) })
            }
          >
            {deleteMapping.isPending ? 'Removing…' : 'Remove mapping'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={toDisable != null} onHide={() => setToDisable(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Disable user?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{toDisable?.username}</strong> can no longer sign in. Their name stays on everything they
          triggered, and you can enable them again at any time.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setToDisable(null)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={updateUser.isPending}
            onClick={() =>
              toDisable &&
              updateUser.mutate({ id: toDisable.id, enabled: false }, { onSettled: () => setToDisable(null) })
            }
          >
            {updateUser.isPending ? 'Disabling…' : 'Disable user'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={passwordTarget != null} onHide={() => setPasswordTarget(null)}>
        <Form onSubmit={submitPassword}>
          <Modal.Header closeButton>
            <Modal.Title className="h6">Set password for {passwordTarget?.username}</Modal.Title>
          </Modal.Header>
          <Modal.Body>
            <Form.Group controlId="new-password">
              <Form.Label>New password</Form.Label>
              <Form.Control
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                minLength={8}
                required
                autoFocus
              />
            </Form.Group>
          </Modal.Body>
          <Modal.Footer>
            <Button variant="outline-secondary" onClick={() => setPasswordTarget(null)}>
              Cancel
            </Button>
            <Button type="submit" disabled={updateUser.isPending}>
              {updateUser.isPending ? 'Saving…' : 'Save'}
            </Button>
          </Modal.Footer>
        </Form>
      </Modal>
    </>
  )
}
