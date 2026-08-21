import { useCallback, useRef, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Col from 'react-bootstrap/Col'
import Form from 'react-bootstrap/Form'
import ListGroup from 'react-bootstrap/ListGroup'
import Modal from 'react-bootstrap/Modal'
import Row from 'react-bootstrap/Row'
import Spinner from 'react-bootstrap/Spinner'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  useAddGroupDevice,
  useAddGroupRole,
  useApplyGroup,
  useAssignableRoles,
  useDeleteGroup,
  useDevices,
  useGroup,
  useGroupBatches,
  useGroupEffectiveRoles,
  useMe,
  useRemoveGroupDevice,
  useRemoveGroupRole,
  useReorderGroupRoles,
  useReplaceGroupVariables,
  useUpdateGroup,
} from '../api/hooks'
import type { VariableEntry } from '../api/types'
import ApplyConfirmModal from '../components/ApplyConfirmModal'
import RoleVariablesForm, { managedVariableNames } from '../components/RoleVariablesForm'
import VariablesEditor from '../components/VariablesEditor'
import { absoluteTime, relativeTime } from '../lib/time'
import { rollup } from './BatchDetail'

export default function GroupDetail() {
  const { id } = useParams()
  const groupId = id ?? ''
  const navigate = useNavigate()

  const { data: group, isLoading, isError, refetch } = useGroup(groupId)
  const { data: batches } = useGroupBatches(groupId)
  const { data: allDevices } = useDevices({}, 0, 500)
  const { data: allRoles } = useAssignableRoles()
  const { data: effectiveRoles } = useGroupEffectiveRoles(groupId)

  const addDevice = useAddGroupDevice(groupId)
  const removeDevice = useRemoveGroupDevice(groupId)
  const addRole = useAddGroupRole(groupId)
  const removeRole = useRemoveGroupRole(groupId)
  const reorderRoles = useReorderGroupRoles(groupId)
  const replaceVariables = useReplaceGroupVariables(groupId)
  const deleteGroup = useDeleteGroup()
  const applyGroup = useApplyGroup()
  const updateGroup = useUpdateGroup(groupId)

  const [deviceToAdd, setDeviceToAdd] = useState('')
  const [roleToAdd, setRoleToAdd] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [confirmApply, setConfirmApply] = useState(false)
  const [canary, setCanary] = useState(false)
  const [movingId, setMovingId] = useState<string | null>(null)
  /** undefined = not editing; the field shows the saved group value. */
  const [intervalDraft, setIntervalDraft] = useState<string | undefined>(undefined)
  const [offlineDraft, setOfflineDraft] = useState<string | undefined>(undefined)
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

  if (isError) {
    return (
      <Alert variant="danger">
        This group could not be loaded — it may have been deleted.{' '}
        <Button size="sm" variant="outline-danger" onClick={() => refetch()}>
          Try again
        </Button>{' '}
        <Link to="/groups">Back to groups</Link>
      </Alert>
    )
  }

  if (isLoading || !group) {
    return <Spinner role="status" aria-label="Loading group" />
  }

  const managedNames = managedVariableNames(effectiveRoles ?? [])
  const customVars = group.variables.filter((v) => v.secret || !managedNames.has(v.name))
  const managedVars = group.variables.filter((v) => !v.secret && managedNames.has(v.name))

  const memberIds = new Set(group.devices.map((d) => d.id))
  const addableDevices = (allDevices?.items ?? []).filter((d) => !memberIds.has(d.id))
  const assignedRoleIds = new Set(group.roles.map((r) => r.roleId))
  const addableRoles = (allRoles ?? []).filter((r) => !assignedRoleIds.has(r.id))

  const move = (assignmentId: string, delta: number) => {
    // Build on the in-flight order so quick successive moves don't
    // overwrite each other with a stale ordering.
    const order =
      reorderRoles.isPending && reorderRoles.variables
        ? [...reorderRoles.variables]
        : group.roles.map((r) => r.id)
    const index = order.indexOf(assignmentId)
    const target = index + delta
    if (index < 0 || target < 0 || target >= order.length) return
    order.splice(index, 1)
    order.splice(target, 0, assignmentId)
    setMovingId(assignmentId)
    reorderRoles.mutate(order, { onSettled: () => setMovingId(null) })
  }

  return (
    <>
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <Link to="/groups">Groups</Link>
          </li>
          <li className="breadcrumb-item active" aria-current="page">
            {group.name}
          </li>
        </ol>
      </nav>

      <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-3">
        <div>
          <h1 className="h3 mb-0">{group.name}</h1>
          {group.description && <p className="text-secondary mb-0">{group.description}</p>}
        </div>
        <span className="d-flex gap-2">
          <Button
            size="sm"
            disabled={applyGroup.isPending || group.devices.length === 0 || group.roles.length === 0}
            onClick={() => setConfirmApply(true)}
          >
            {applyGroup.isPending ? 'Queuing…' : `Apply to ${group.devices.length} ${group.devices.length === 1 ? 'device' : 'devices'}`}
          </Button>
          <Button variant="outline-danger" size="sm" onClick={() => setConfirmDelete(true)}>
            Delete group
          </Button>
        </span>
      </div>

      <Row className="g-3">
        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Devices ({group.devices.length})</Card.Title>
              <ListGroup variant="flush" className="mb-3">
                {group.devices.length === 0 && <div className="text-secondary">No devices in this group yet.</div>}
                {group.devices.map((device) => (
                  <ListGroup.Item key={device.id} className="d-flex justify-content-between align-items-center px-0">
                    <span>
                      <Badge bg={device.online ? 'success' : 'danger'} className="me-2">
                        {device.online ? 'Online' : 'Offline'}
                      </Badge>
                      {device.hostname}
                    </span>
                    <Button
                      size="sm"
                      variant="outline-danger"
                      onClick={() => removeDevice.mutate(device.id)}
                      aria-label={`Remove ${device.hostname} from group`}
                    >
                      Remove
                    </Button>
                  </ListGroup.Item>
                ))}
              </ListGroup>
              <Form
                className="d-flex gap-2"
                onSubmit={(e) => {
                  e.preventDefault()
                  if (deviceToAdd) addDevice.mutate(deviceToAdd, { onSuccess: () => setDeviceToAdd('') })
                }}
              >
                <Form.Select
                  size="sm"
                  aria-label="Device to add"
                  value={deviceToAdd}
                  onChange={(e) => setDeviceToAdd(e.target.value)}
                >
                  <option value="">Select a device…</option>
                  {addableDevices.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.hostname}
                    </option>
                  ))}
                </Form.Select>
                <Button size="sm" type="submit" disabled={!deviceToAdd || addDevice.isPending}>
                  Add
                </Button>
              </Form>
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Assigned roles (run in this order)</Card.Title>
              <ListGroup variant="flush" className="mb-3">
                {group.roles.length === 0 && (
                  <div className="text-secondary">
                    No roles assigned. Roles come from your <Link to="/sources">Ansible sources</Link>.
                  </div>
                )}
                {group.roles.map((assignment, index) => (
                  <ListGroup.Item key={assignment.id} className="d-flex justify-content-between align-items-center px-0">
                    <span>
                      <span className="text-secondary me-2">{index + 1}.</span>
                      {assignment.displayName}
                      <span className="text-secondary small ms-2">({assignment.repository})</span>
                      {assignment.missing && (
                        <Badge bg="warning" text="dark" className="ms-2">
                          No longer in repository
                        </Badge>
                      )}
                    </span>
                    <span className="d-flex gap-1">
                      <Button
                        size="sm"
                        variant="outline-secondary"
                        disabled={index === 0 || (reorderRoles.isPending && movingId === assignment.id)}
                        onClick={() => move(assignment.id, -1)}
                        aria-label={`Move ${assignment.displayName} up`}
                      >
                        ↑
                      </Button>
                      <Button
                        size="sm"
                        variant="outline-secondary"
                        disabled={
                          index === group.roles.length - 1 ||
                          (reorderRoles.isPending && movingId === assignment.id)
                        }
                        onClick={() => move(assignment.id, 1)}
                        aria-label={`Move ${assignment.displayName} down`}
                      >
                        ↓
                      </Button>
                      <Button
                        size="sm"
                        variant="outline-danger"
                        onClick={() => removeRole.mutate(assignment.id)}
                        aria-label={`Remove role ${assignment.displayName}`}
                      >
                        Remove
                      </Button>
                    </span>
                  </ListGroup.Item>
                ))}
              </ListGroup>
              <Form
                className="d-flex gap-2"
                onSubmit={(e) => {
                  e.preventDefault()
                  if (roleToAdd) addRole.mutate(roleToAdd, { onSuccess: () => setRoleToAdd('') })
                }}
              >
                <Form.Select
                  size="sm"
                  aria-label="Role to assign"
                  value={roleToAdd}
                  onChange={(e) => setRoleToAdd(e.target.value)}
                >
                  <option value="">Select a role…</option>
                  {addableRoles.map((role) => (
                    <option key={role.id} value={role.id}>
                      {role.displayName} ({role.repository})
                    </option>
                  ))}
                </Form.Select>
                <Button size="sm" type="submit" disabled={!roleToAdd || addRole.isPending}>
                  Assign
                </Button>
              </Form>
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Role variables</Card.Title>
              <Card.Text className="text-secondary small">
                Every variable of the roles this group runs, pre-filled with its default. Change a value to
                override it for all devices of this group; per-device overrides (on the device page) win.
              </Card.Text>
              <RoleVariablesForm
                roles={effectiveRoles ?? []}
                variables={group.variables}
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
        <Col xs={12} lg={6}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Check-in & alerts</Card.Title>
              <Form
                onSubmit={(e) => {
                  e.preventDefault()
                  const value = (intervalDraft ?? '').trim()
                  updateGroup.mutate(
                    { pollIntervalSeconds: value === '' ? null : Number(value) },
                    { onSuccess: () => setIntervalDraft(undefined) },
                  )
                }}
              >
                <Form.Label className="mb-1" htmlFor="group-poll-interval">
                  Check-in interval
                </Form.Label>
                <Form.Text className="d-block text-secondary mb-1">
                  How often devices in this group contact the server. Empty = server default (60 seconds); a
                  device in several groups uses the smallest interval.
                </Form.Text>
                <div className="d-flex align-items-center gap-2 mb-3">
                  <Form.Control
                    id="group-poll-interval"
                    type="number"
                    min={10}
                    max={86400}
                    placeholder="default"
                    value={intervalDraft ?? group.pollIntervalSeconds?.toString() ?? ''}
                    onChange={(e) => setIntervalDraft(e.target.value)}
                    style={{ maxWidth: '9rem' }}
                  />
                  <span className="text-secondary">seconds</span>
                  <Button size="sm" type="submit" disabled={updateGroup.isPending || intervalDraft === undefined}>
                    {updateGroup.isPending ? 'Saving…' : 'Save'}
                  </Button>
                </div>
              </Form>
              <Form
                onSubmit={(e) => {
                  e.preventDefault()
                  const value = (offlineDraft ?? '').trim()
                  updateGroup.mutate(
                    { offlineAlertSeconds: value === '' ? null : Number(value) },
                    { onSuccess: () => setOfflineDraft(undefined) },
                  )
                }}
              >
                <Form.Label className="mb-1" htmlFor="group-offline-alert">
                  Offline alert after
                </Form.Label>
                <Form.Text className="d-block text-secondary mb-1">
                  How long a device may be silent before an offline alert fires (e.g. long for notebooks, short
                  for kiosks). Empty = server default (10 minutes); the most tolerant group wins.
                </Form.Text>
                <div className="d-flex align-items-center gap-2">
                  <Form.Control
                    id="group-offline-alert"
                    type="number"
                    min={60}
                    max={604800}
                    placeholder="default"
                    value={offlineDraft ?? group.offlineAlertSeconds?.toString() ?? ''}
                    onChange={(e) => setOfflineDraft(e.target.value)}
                    style={{ maxWidth: '9rem' }}
                  />
                  <span className="text-secondary">seconds</span>
                  <Button size="sm" type="submit" disabled={updateGroup.isPending || offlineDraft === undefined}>
                    {updateGroup.isPending ? 'Saving…' : 'Save'}
                  </Button>
                </div>
              </Form>
            </Card.Body>
          </Card>
        </Col>

        <Col xs={12}>
          <Card>
            <Card.Body>
              <Card.Title className="h6">Recent rollouts</Card.Title>
              {(batches ?? []).length === 0 ? (
                <p className="text-secondary mb-0">
                  No rollouts yet — "Apply to …" starts one and tracks it here.
                </p>
              ) : (
                <ListGroup variant="flush" className="mb-0">
                  {batches!.map((batch) => (
                    <ListGroup.Item
                      key={batch.id}
                      className="d-flex justify-content-between align-items-center gap-2 px-0 flex-wrap"
                    >
                      <span>
                        <Link to={`/batches/${batch.id}`}>Rollout #{batch.id.slice(0, 8)}</Link>{' '}
                        <span className="text-secondary">— {rollup(batch).text}</span>
                      </span>
                      <span className="text-secondary small" title={absoluteTime(batch.createdAt)}>
                        {relativeTime(batch.createdAt)}
                        {batch.triggeredBy ? ` by ${batch.triggeredBy}` : ''}
                      </span>
                    </ListGroup.Item>
                  ))}
                </ListGroup>
              )}
            </Card.Body>
          </Card>
        </Col>
      </Row>

      <ApplyConfirmModal
        show={confirmApply}
        onHide={() => setConfirmApply(false)}
        pending={applyGroup.isPending}
        roles={effectiveRoles ?? []}
        deviceCount={group.devices.length}
        offlineCount={group.devices.filter((d) => !d.online).length}
        triggeredBy={me?.username}
        onConfirm={(runAfter) =>
          applyGroup.mutate(
            { groupId, canary, runAfter },
            {
              onSuccess: (batch) => navigate(`/batches/${batch.id}`),
              onSettled: () => setConfirmApply(false),
            },
          )
        }
      >
        {group.devices.length > 1 && (
          <Form.Check
            id="canary-first"
            className="mt-3"
            label="Canary first — apply to 1 device, continue to the rest only after it succeeded"
            checked={canary}
            onChange={(e) => setCanary(e.target.checked)}
          />
        )}
      </ApplyConfirmModal>

      <Modal show={confirmDelete} onHide={() => setConfirmDelete(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Delete group?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{group.name}</strong> and its role assignments and variables will be deleted. The{' '}
          {group.devices.length} {group.devices.length === 1 ? 'device' : 'devices'} in it are not removed from
          Svenager. This cannot be undone.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setConfirmDelete(false)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={deleteGroup.isPending}
            onClick={() => deleteGroup.mutate(groupId, { onSuccess: () => navigate('/groups') })}
          >
            {deleteGroup.isPending ? 'Deleting…' : 'Delete group'}
          </Button>
        </Modal.Footer>
      </Modal>
    </>
  )
}
