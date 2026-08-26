import { useCallback, useRef, useState } from 'react'
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
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  useApplyDevice,
  useDevice,
  useDeviceEffectiveRoles,
  useDeviceRemoteSessions,
  useGroups,
  useJobs,
  useMe,
  usePreviewDevice,
  useRebootDevice,
  useRenameDevice,
  useReplaceDeviceVariables,
  useSetDeviceGroups,
  useSetDeviceStatus,
  useUpdateAgent,
} from '../api/hooks'
import type { VariableEntry } from '../api/types'
import ApplyConfirmModal from '../components/ApplyConfirmModal'
import JobsTable from '../components/JobsTable'
import RoleVariablesForm, { managedVariableNames } from '../components/RoleVariablesForm'
import VariablesEditor from '../components/VariablesEditor'
import { absoluteTime, relativeTime } from '../lib/time'

export default function DeviceDetail() {
  const { id } = useParams()
  const JOBS_PAGE_SIZE = 50
  const { data: device, isLoading } = useDevice(id ?? null)
  const { data: allGroups } = useGroups()
  const [jobsOffset, setJobsOffset] = useState(0)
  const { data: jobs } = useJobs({ deviceId: id }, jobsOffset, JOBS_PAGE_SIZE)
  const deviceJobs = jobs?.items ?? []
  const jobsTotal = jobs?.total ?? 0
  const lastJobsOffset = Math.floor(Math.max(jobsTotal - 1, 0) / JOBS_PAGE_SIZE) * JOBS_PAGE_SIZE
  // Newest apply first on the first page; a stuck one needs the operator.
  const latestApply = jobsOffset === 0 ? deviceJobs.find((j) => j.type === 'APPLY_CONFIG') : undefined
  const retriesExhausted = latestApply?.retriesExhausted ?? false
  const setGroups = useSetDeviceGroups(id ?? '')
  const replaceVariables = useReplaceDeviceVariables(id ?? '')
  const applyDevice = useApplyDevice()
  const previewDevice = usePreviewDevice()
  const { data: remoteSessions } = useDeviceRemoteSessions(id ?? null)
  const { data: effectiveRoles } = useDeviceEffectiveRoles(id ?? null)
  const renameDevice = useRenameDevice(id ?? '')
  const setStatus = useSetDeviceStatus()
  const updateAgent = useUpdateAgent()
  const rebootDevice = useRebootDevice()
  const [confirmAgentUpdate, setConfirmAgentUpdate] = useState(false)
  const [editingName, setEditingName] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [pendingGroupId, setPendingGroupId] = useState<string | null>(null)
  const [confirmDisable, setConfirmDisable] = useState(false)
  const [confirmApply, setConfirmApply] = useState(false)
  const [confirmReboot, setConfirmReboot] = useState(false)
  const { data: me } = useMe()
  // Live getters so saving one variable editor never clobbers the other's
  // unsaved edits (both PUT the full variable set).
  const roleFormLive = useRef<(() => VariableEntry[]) | null>(null)
  const customLive = useRef<(() => VariableEntry[]) | null>(null)
  const registerRoleForm = useCallback((get: () => VariableEntry[]) => {
    roleFormLive.current = get
  }, [])
  const registerCustom = useCallback((get: () => VariableEntry[]) => {
    customLive.current = get
  }, [])
  const navigate = useNavigate()

  if (isLoading || !device) {
    return <Spinner role="status" aria-label="Loading device" />
  }

  const managedNames = managedVariableNames(effectiveRoles ?? [])
  const customVars = device.variables.filter((v) => v.secret || !managedNames.has(v.name))
  const managedVars = device.variables.filter((v) => !v.secret && managedNames.has(v.name))

  const memberIds = new Set(device.groups.map((g) => g.id))

  const toggleGroup = (groupId: string, checked: boolean) => {
    // Build on the in-flight selection so quick successive toggles don't
    // overwrite each other with stale membership data.
    const next =
      setGroups.isPending && setGroups.variables ? new Set(setGroups.variables) : new Set(memberIds)
    if (checked) next.add(groupId)
    else next.delete(groupId)
    setPendingGroupId(groupId)
    setGroups.mutate([...next], { onSettled: () => setPendingGroupId(null) })
  }

  return (
    <>
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <Link to="/devices">Devices</Link>
          </li>
          <li className="breadcrumb-item active" aria-current="page">
            {device.hostname}
          </li>
        </ol>
      </nav>

      <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
        {editingName ? (
          <Form
            className="d-flex align-items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              renameDevice.mutate(nameDraft.trim(), { onSuccess: () => setEditingName(false) })
            }}
          >
            <Form.Control
              autoFocus
              aria-label="Device name"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              pattern="[A-Za-z0-9][A-Za-z0-9-]{0,62}"
              title="1-63 letters, digits or dashes"
              style={{ maxWidth: '18rem' }}
            />
            <Button size="sm" type="submit" disabled={renameDevice.isPending}>
              {renameDevice.isPending ? 'Saving…' : 'Save'}
            </Button>
            <Button size="sm" variant="outline-secondary" onClick={() => setEditingName(false)}>
              Cancel
            </Button>
          </Form>
        ) : (
          <>
            <h1 className="h3 mb-0">{device.hostname}</h1>
            <Button
              size="sm"
              variant="link"
              className="p-0"
              title="The name becomes the device's hostname at its next check-in"
              onClick={() => {
                setNameDraft(device.hostname)
                setEditingName(true)
              }}
            >
              Rename
            </Button>
          </>
        )}
        {renameDevice.isError && <span className="text-danger small">Rename failed — 1-63 letters, digits or dashes.</span>}
        {device.status === 'DISABLED' ? (
          <Badge bg="secondary">Disabled</Badge>
        ) : (
          <Badge bg={device.online ? 'success' : 'danger'}>{device.online ? 'Online' : 'Offline'}</Badge>
        )}
        <Button
          size="sm"
          variant={device.status === 'DISABLED' ? 'outline-success' : 'outline-secondary'}
          className="ms-auto"
          disabled={setStatus.isPending}
          onClick={() =>
            device.status === 'DISABLED'
              ? setStatus.mutate({ id: device.id, status: 'ACTIVE' })
              : setConfirmDisable(true)
          }
        >
          {device.status === 'DISABLED' ? (setStatus.isPending ? 'Enabling…' : 'Enable') : 'Disable'}
        </Button>
        <Button
          size="sm"
          variant="outline-warning"
          disabled={!device.online || rebootDevice.isPending}
          title={device.online ? 'Restart this device' : 'The device must be online'}
          onClick={() => setConfirmReboot(true)}
        >
          {rebootDevice.isPending ? 'Queuing…' : 'Reboot'}
        </Button>
        <Button
          size="sm"
          variant="outline-primary"
          disabled={!device.online}
          title={device.online ? 'Open a live view of this device' : 'The device must be online'}
          onClick={() => navigate(`/devices/${device.id}/remote`)}
        >
          Remote view
        </Button>
        <Button
          size="sm"
          variant="outline-primary"
          disabled={!device.online}
          title={device.online ? 'Open a shell on this device' : 'The device must be online'}
          onClick={() => navigate(`/devices/${device.id}/shell`)}
        >
          Shell
        </Button>
        <Button
          size="sm"
          variant="outline-primary"
          disabled={previewDevice.isPending}
          title="Runs the configuration in check mode — shows what would change without changing anything"
          onClick={() =>
            previewDevice.mutate(device.id, { onSuccess: (job) => navigate(`/jobs/${job.id}`) })
          }
        >
          {previewDevice.isPending ? 'Queuing…' : 'Preview changes'}
        </Button>
        <Button size="sm" disabled={applyDevice.isPending} onClick={() => setConfirmApply(true)}>
          {applyDevice.isPending ? 'Queuing…' : 'Apply configuration'}
        </Button>
      </div>

      {retriesExhausted && latestApply && (
        <Alert variant="warning" className="d-flex align-items-center gap-2 flex-wrap">
          <div className="flex-grow-1">
            <strong>Configuration is not applied.</strong> The last apply failed {latestApply.attempt} times and
            automatic retries are exhausted; nothing runs again until you apply or the configuration changes. Fix
            the cause on the device (see the <Link to={`/jobs/${latestApply.id}`}>job log</Link>), then apply again.
          </div>
          <Button size="sm" variant="warning" disabled={applyDevice.isPending} onClick={() => setConfirmApply(true)}>
            Apply configuration
          </Button>
        </Alert>
      )}

      <Row className="g-3">
        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Overview</Card.Title>
              <Table size="sm" responsive className="mb-0">
                <tbody>
                  <tr>
                    <th scope="row">Last contact</th>
                    <td title={absoluteTime(device.lastContactAt)}>{relativeTime(device.lastContactAt)}</td>
                  </tr>
                  <tr>
                    <th scope="row">Last job</th>
                    <td title={absoluteTime(device.lastJobAt)}>{relativeTime(device.lastJobAt)}</td>
                  </tr>
                  <tr>
                    <th scope="row">IP address</th>
                    <td>{device.lastIp ? <code>{device.lastIp}</code> : '—'}</td>
                  </tr>
                  <tr>
                    <th scope="row">Agent version</th>
                    <td>
                      {device.agentVersion ?? '—'}{' '}
                      <Button
                        size="sm"
                        variant="link"
                        className="p-0 align-baseline"
                        onClick={() => setConfirmAgentUpdate(true)}
                      >
                        Update agent
                      </Button>
                    </td>
                  </tr>
                  <tr>
                    <th scope="row">Enrolled</th>
                    <td title={absoluteTime(device.enrolledAt)}>{relativeTime(device.enrolledAt)}</td>
                  </tr>
                  <tr>
                    <th scope="row">Device ID</th>
                    <td>
                      <code>{device.id}</code>
                    </td>
                  </tr>
                </tbody>
              </Table>
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Groups</Card.Title>
              {(allGroups ?? []).length === 0 ? (
                <p className="text-secondary mb-0">
                  No groups exist yet — create one on the <Link to="/groups">Groups</Link> page.
                </p>
              ) : (
                <Form>
                  {allGroups!.map((group) => (
                    <Form.Check
                      key={group.id}
                      id={`device-group-${group.id}`}
                      label={group.name}
                      checked={memberIds.has(group.id)}
                      disabled={setGroups.isPending && pendingGroupId === group.id}
                      onChange={(e) => toggleGroup(group.id, e.target.checked)}
                    />
                  ))}
                </Form>
              )}
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Role variables</Card.Title>
              <Card.Text className="text-secondary small">
                Every variable of the roles this device runs, pre-filled with its default. Overrides here apply
                to this single device and win over group values.
              </Card.Text>
              <RoleVariablesForm
                roles={effectiveRoles ?? []}
                variables={device.variables}
                register={registerRoleForm}
                onSave={(owned) =>
                  replaceVariables.mutate([...owned, ...(customLive.current?.() ?? customVars)])
                }
                saving={replaceVariables.isPending}
                error={replaceVariables.isError}
              />
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Custom variables</Card.Title>
              <Card.Text className="text-secondary small">
                Free-form extra variables (and secrets) beyond what the roles declare.
              </Card.Text>
              <VariablesEditor
                variables={customVars}
                register={registerCustom}
                onSave={(owned) =>
                  replaceVariables.mutate([...owned, ...(roleFormLive.current?.() ?? managedVars)])
                }
                saving={replaceVariables.isPending}
                error={replaceVariables.isError}
              />
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Job history</Card.Title>
              {deviceJobs.length > 0 ? (
                <JobsTable jobs={deviceJobs} showDevice={false} />
              ) : (
                <p className="text-secondary mb-0">Nothing was executed on this device yet.</p>
              )}
              {jobsTotal > JOBS_PAGE_SIZE && (
                <div className="d-flex align-items-center gap-2 mt-2">
                  <Button
                    size="sm"
                    variant="outline-secondary"
                    disabled={jobsOffset === 0}
                    onClick={() => setJobsOffset(Math.max(jobsOffset - JOBS_PAGE_SIZE, 0))}
                  >
                    Newer
                  </Button>
                  <Button
                    size="sm"
                    variant="outline-secondary"
                    disabled={jobsOffset >= lastJobsOffset}
                    onClick={() => setJobsOffset(Math.min(jobsOffset + JOBS_PAGE_SIZE, lastJobsOffset))}
                  >
                    Older
                  </Button>
                  <span className="text-secondary small">
                    {jobsOffset + 1}–{Math.min(jobsOffset + deviceJobs.length, jobsTotal)} of {jobsTotal}
                  </span>
                </div>
              )}
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Remote sessions</Card.Title>
              {(remoteSessions ?? []).length === 0 ? (
                <p className="text-secondary mb-0">No one viewed this device remotely yet.</p>
              ) : (
                <Table size="sm" responsive className="mb-0">
                  <thead>
                    <tr>
                      <th>Started</th>
                      <th>By</th>
                      <th>Status</th>
                      <th>Ended</th>
                    </tr>
                  </thead>
                  <tbody>
                    {remoteSessions!.map((s) => (
                      <tr key={s.sessionId}>
                        <td title={absoluteTime(s.createdAt)}>{relativeTime(s.createdAt)}</td>
                        <td>{s.requestedBy ?? '—'}</td>
                        <td>
                          <Badge bg={s.status === 'CLOSED' ? 'secondary' : 'success'}>
                            {s.status === 'CLOSED' ? 'Closed' : 'Open'}
                          </Badge>
                        </td>
                        <td>{s.closeReason ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              )}
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Reported facts</Card.Title>
              {Object.keys(device.facts).length === 0 ? (
                <p className="text-secondary mb-0">No facts reported yet.</p>
              ) : (
                <Table size="sm" responsive className="mb-0">
                  <tbody>
                    {Object.entries(device.facts).map(([key, value]) => (
                      <tr key={key}>
                        <th scope="row">
                          <code>{key}</code>
                        </th>
                        <td style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{String(value)}</td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              )}
            </Card.Body>
          </Card>
        </Col>
      </Row>

      <ApplyConfirmModal
        show={confirmApply}
        onHide={() => setConfirmApply(false)}
        pending={applyDevice.isPending}
        roles={effectiveRoles ?? []}
        deviceCount={1}
        offlineCount={device.online ? 0 : 1}
        triggeredBy={me?.username}
        onConfirm={(runAfter) =>
          applyDevice.mutate(
            { deviceId: device.id, runAfter },
            {
              onSuccess: (job) => navigate(`/jobs/${job.id}`),
              onSettled: () => setConfirmApply(false),
            },
          )
        }
      />

      <Modal show={confirmAgentUpdate} onHide={() => setConfirmAgentUpdate(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Update agent?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          At its next check-in, <strong>{device.hostname}</strong> downloads the agent binary published on this
          server, verifies its signature and restarts itself. The device must have an update key configured
          (<code>update_public_key</code>); the update is rejected otherwise.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setConfirmAgentUpdate(false)}>
            Cancel
          </Button>
          <Button
            disabled={updateAgent.isPending}
            onClick={() =>
              updateAgent.mutate(
                { deviceId: device.id },
                {
                  onSuccess: (job) => navigate(`/jobs/${job.id}`),
                  onSettled: () => setConfirmAgentUpdate(false),
                },
              )
            }
          >
            {updateAgent.isPending ? 'Queuing…' : 'Update agent'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={confirmReboot} onHide={() => setConfirmReboot(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Reboot device?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          At its next check-in, <strong>{device.hostname}</strong> restarts. It goes offline for the duration of
          the reboot and reconnects on its own afterwards.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setConfirmReboot(false)}>
            Cancel
          </Button>
          <Button
            variant="warning"
            disabled={rebootDevice.isPending}
            onClick={() =>
              rebootDevice.mutate(device.id, {
                onSuccess: (job) => navigate(`/jobs/${job.id}`),
                onSettled: () => setConfirmReboot(false),
              })
            }
          >
            {rebootDevice.isPending ? 'Queuing…' : 'Reboot device'}
          </Button>
        </Modal.Footer>
      </Modal>

      <Modal show={confirmDisable} onHide={() => setConfirmDisable(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Disable device?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{device.hostname}</strong> will be rejected at its next check-in: no new jobs, no remote view,
          no status updates. The device keeps running its current configuration. You can enable it again at any
          time.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setConfirmDisable(false)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={setStatus.isPending}
            onClick={() =>
              setStatus.mutate(
                { id: device.id, status: 'DISABLED' },
                { onSettled: () => setConfirmDisable(false) },
              )
            }
          >
            {setStatus.isPending ? 'Disabling…' : 'Disable device'}
          </Button>
        </Modal.Footer>
      </Modal>
    </>
  )
}
