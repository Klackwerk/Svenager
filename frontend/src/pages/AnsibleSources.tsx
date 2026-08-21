import { useState, type FormEvent } from 'react'
import Accordion from 'react-bootstrap/Accordion'
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
  useCreateRepository,
  useDeleteRepository,
  useRepositories,
  useRepositoryRoles,
  useSyncRepository,
  useUpdateRepository,
} from '../api/hooks'
import type { RepositorySummary } from '../api/types'
import { useToast } from '../components/ToastProvider'
import { absoluteTime, relativeTime } from '../lib/time'

const STATUS: Record<RepositorySummary['syncStatus'], { label: string; bg: string }> = {
  NEVER: { label: 'Not synced yet', bg: 'secondary' },
  SYNCING: { label: 'Syncing…', bg: 'info' },
  OK: { label: 'Synced', bg: 'success' },
  ERROR: { label: 'Sync failed', bg: 'danger' },
}

function RepositoryRoles({ repositoryId }: { repositoryId: string }) {
  const { data: roles, isLoading } = useRepositoryRoles(repositoryId)

  if (isLoading) return <Spinner size="sm" role="status" aria-label="Loading roles" />
  if (!roles || roles.length === 0) return <p className="text-secondary mb-0">No roles discovered yet — run a sync.</p>

  return (
    <Accordion alwaysOpen>
      {roles.map((role) => (
        <Accordion.Item key={role.id} eventKey={String(role.id)}>
          <Accordion.Header>
            <span>
              {role.displayName}
              {!role.userAssignable && (
                <Badge bg="secondary" className="ms-2">
                  Internal
                </Badge>
              )}
              {role.missing && (
                <Badge bg="warning" text="dark" className="ms-2">
                  No longer in repository
                </Badge>
              )}
            </span>
          </Accordion.Header>
          <Accordion.Body>
            {role.description && <p>{role.description}</p>}
            {Object.keys(role.argumentSpec).length > 0 ? (
              <Table responsive size="sm">
                <thead>
                  <tr>
                    <th>Variable</th>
                    <th>Type</th>
                    <th>Required</th>
                    <th>Default</th>
                    <th>Description</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(role.argumentSpec).map(([name, option]) => (
                    <tr key={name}>
                      <td>
                        <code>{name}</code>
                      </td>
                      <td>{option.type ?? '—'}</td>
                      <td>{option.required ? 'yes' : 'no'}</td>
                      <td>
                        <code>
                          {JSON.stringify(option.default ?? role.defaults[name]) ?? '—'}
                        </code>
                      </td>
                      <td>{Array.isArray(option.description) ? option.description.join(' ') : option.description}</td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            ) : (
              <p className="text-secondary mb-0">This role documents no variables (meta/argument_specs.yml).</p>
            )}
          </Accordion.Body>
        </Accordion.Item>
      ))}
    </Accordion>
  )
}

export default function AnsibleSources() {
  const { data: repositories, isLoading, isError } = useRepositories()
  const createRepository = useCreateRepository()
  const updateRepository = useUpdateRepository()
  const deleteRepository = useDeleteRepository()
  const syncRepository = useSyncRepository()

  const [name, setName] = useState('')
  const [gitUrl, setGitUrl] = useState('')
  const [branch, setBranch] = useState('main')
  const [generateDeployKey, setGenerateDeployKey] = useState(false)
  const [showKeyFor, setShowKeyFor] = useState<string | null>(null)
  const [editing, setEditing] = useState<{ id: string; name: string; gitUrl: string; branch: string } | null>(null)
  const [toRemove, setToRemove] = useState<RepositorySummary | null>(null)
  const [copiedKeyFor, setCopiedKeyFor] = useState<string | null>(null)
  const toast = useToast()

  const copyKey = async (repo: RepositorySummary) => {
    try {
      await navigator.clipboard.writeText(repo.deployKeyPublic!)
      setCopiedKeyFor(repo.id)
    } catch {
      toast({ variant: 'danger', text: 'Copying failed — select the key and copy it manually.' })
    }
  }

  const submitEdit = (event: FormEvent) => {
    event.preventDefault()
    if (!editing) return
    updateRepository.mutate(
      {
        id: editing.id,
        name: editing.name.trim(),
        gitUrl: editing.gitUrl.trim(),
        branch: editing.branch.trim() || 'main',
      },
      { onSuccess: (repo) => { setEditing(null); syncRepository.mutate(repo.id) } },
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    createRepository.mutate(
      { name: name.trim(), gitUrl: gitUrl.trim(), branch: branch.trim() || 'main', generateDeployKey },
      {
        onSuccess: (repo) => {
          setName('')
          setGitUrl('')
          setBranch('main')
          setGenerateDeployKey(false)
          if (repo.deployKeyPublic) setShowKeyFor(repo.id)
        },
      },
    )
  }

  return (
    <>
      <h1 className="h3 mb-3">Ansible sources</h1>
      <p className="text-secondary">
        Git repositories with Ansible roles, maintained by your Ansible professionals. Svenager analyzes them and
        offers the discovered roles for assignment to groups — it never executes repository content on the server.
      </p>

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">Add repository</Card.Title>
          {createRepository.isError && (
            <Alert variant="danger">The repository could not be added — is the name already in use?</Alert>
          )}
          <Form onSubmit={submit}>
            <Row className="g-3 align-items-end">
              <Col xs={12} md={3}>
                <Form.Group controlId="repo-name">
                  <Form.Label>Name</Form.Label>
                  <Form.Control value={name} onChange={(e) => setName(e.target.value)} required />
                </Form.Group>
              </Col>
              <Col xs={12} md={4}>
                <Form.Group controlId="repo-url">
                  <Form.Label>Git URL</Form.Label>
                  <Form.Control
                    placeholder="git@github.com:org/ansible-config.git"
                    value={gitUrl}
                    onChange={(e) => setGitUrl(e.target.value)}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={6} md={2}>
                <Form.Group controlId="repo-branch">
                  <Form.Label>Branch or tag</Form.Label>
                  <Form.Control
                    value={branch}
                    onChange={(e) => setBranch(e.target.value)}
                    placeholder="main or v1.0"
                  />
                </Form.Group>
              </Col>
              <Col xs={6} md={2}>
                <Form.Check
                  id="repo-deploy-key"
                  label="Generate deploy key"
                  checked={generateDeployKey}
                  onChange={(e) => setGenerateDeployKey(e.target.checked)}
                />
              </Col>
              <Col xs={12} md={1}>
                <Button type="submit" className="w-100" disabled={createRepository.isPending}>
                  {createRepository.isPending ? '…' : 'Add'}
                </Button>
              </Col>
            </Row>
          </Form>
        </Card.Body>
      </Card>

      {isLoading && <Spinner role="status" aria-label="Loading repositories" />}
      {isError && <Alert variant="danger">Repositories could not be loaded. Retrying automatically…</Alert>}
      {repositories && repositories.length === 0 && (
        <Alert variant="secondary">No repositories yet — add your Ansible configuration repository above.</Alert>
      )}

      {repositories?.map((repo) => {
        const status = STATUS[repo.syncStatus]
        return (
          <Card key={repo.id} className="mb-3">
            <Card.Body>
              <div className="d-flex justify-content-between align-items-start flex-wrap gap-2">
                <div>
                  <Card.Title className="h6 mb-1">
                    {repo.name} <Badge bg={status.bg}>{status.label}</Badge>
                  </Card.Title>
                  <div className="text-secondary small">
                    <code>{repo.gitUrl}</code> · ref <code>{repo.branch}</code>
                    {repo.lastSyncedAt && (
                      <span title={absoluteTime(repo.lastSyncedAt)}> · synced {relativeTime(repo.lastSyncedAt)}</span>
                    )}
                    {repo.lastCommit && <span> · commit <code>{repo.lastCommit.slice(0, 10)}</code></span>}
                    {' '}· {repo.roleCount} {repo.roleCount === 1 ? 'role' : 'roles'}
                  </div>
                </div>
                <div className="d-flex gap-2">
                  <Button
                    size="sm"
                    variant="outline-secondary"
                    onClick={() =>
                      setEditing({ id: repo.id, name: repo.name, gitUrl: repo.gitUrl, branch: repo.branch })
                    }
                  >
                    Edit
                  </Button>
                  {repo.deployKeyPublic && (
                    <Button
                      size="sm"
                      variant="outline-secondary"
                      onClick={() => setShowKeyFor(showKeyFor === repo.id ? null : repo.id)}
                    >
                      Deploy key
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="outline-primary"
                    disabled={syncRepository.isPending && syncRepository.variables === repo.id}
                    onClick={() => syncRepository.mutate(repo.id)}
                  >
                    {syncRepository.isPending && syncRepository.variables === repo.id ? 'Syncing…' : 'Sync now'}
                  </Button>
                  <Button size="sm" variant="outline-danger" onClick={() => setToRemove(repo)}>
                    Remove
                  </Button>
                </div>
              </div>

              {repo.syncError && (
                <Alert variant={repo.syncStatus === 'ERROR' ? 'danger' : 'warning'} className="mt-3 mb-0">
                  <pre className="mb-0 small text-wrap">{repo.syncError}</pre>
                </Alert>
              )}

              {showKeyFor === repo.id && repo.deployKeyPublic && (
                <Alert variant="secondary" className="mt-3 mb-0">
                  <p className="mb-2 small">
                    Add this public key as a read-only deploy key to the repository (the private key never leaves the
                    server):
                  </p>
                  <pre className="mb-2 small text-wrap">{repo.deployKeyPublic}</pre>
                  <Button
                    size="sm"
                    variant={copiedKeyFor === repo.id ? 'success' : 'outline-secondary'}
                    onClick={() => copyKey(repo)}
                  >
                    {copiedKeyFor === repo.id ? 'Copied ✓' : 'Copy public key'}
                  </Button>
                </Alert>
              )}

              <div className="mt-3">
                <RepositoryRoles repositoryId={repo.id} />
              </div>
            </Card.Body>
          </Card>
        )
      })}

      <Modal show={toRemove != null} onHide={() => setToRemove(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Remove repository?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{toRemove?.name}</strong> will be removed, along with its discovered roles and their group
          assignments — groups using these roles stop running them. This cannot be undone.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setToRemove(null)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={deleteRepository.isPending}
            onClick={() => toRemove && deleteRepository.mutate(toRemove.id, { onSettled: () => setToRemove(null) })}
          >
            {deleteRepository.isPending ? 'Removing…' : 'Remove repository'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={editing != null} onHide={() => setEditing(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Edit repository</Modal.Title>
        </Modal.Header>
        <Form onSubmit={submitEdit}>
          <Modal.Body>
            {updateRepository.isError && (
              <Alert variant="danger">The repository could not be updated (name may already exist).</Alert>
            )}
            <Form.Group className="mb-3" controlId="edit-repo-name">
              <Form.Label>Name</Form.Label>
              <Form.Control
                value={editing?.name ?? ''}
                onChange={(e) => setEditing((c) => (c ? { ...c, name: e.target.value } : c))}
                required
              />
            </Form.Group>
            <Form.Group className="mb-3" controlId="edit-repo-url">
              <Form.Label>Git URL</Form.Label>
              <Form.Control
                value={editing?.gitUrl ?? ''}
                onChange={(e) => setEditing((c) => (c ? { ...c, gitUrl: e.target.value } : c))}
                required
              />
              <Form.Text className="text-secondary">
                Discovered roles and assignments are kept; the next sync re-clones from the new location.
              </Form.Text>
            </Form.Group>
            <Form.Group controlId="edit-repo-branch">
              <Form.Label>Branch or tag</Form.Label>
              <Form.Control
                value={editing?.branch ?? ''}
                onChange={(e) => setEditing((c) => (c ? { ...c, branch: e.target.value } : c))}
                placeholder="main or v1.0"
              />
            </Form.Group>
          </Modal.Body>
          <Modal.Footer>
            <Button variant="secondary" onClick={() => setEditing(null)}>
              Cancel
            </Button>
            <Button type="submit" disabled={updateRepository.isPending}>
              {updateRepository.isPending ? 'Saving…' : 'Save & sync'}
            </Button>
          </Modal.Footer>
        </Form>
      </Modal>
    </>
  )
}
