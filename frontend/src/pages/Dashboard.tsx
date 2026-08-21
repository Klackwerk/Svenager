import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Card from 'react-bootstrap/Card'
import Col from 'react-bootstrap/Col'
import Row from 'react-bootstrap/Row'
import Spinner from 'react-bootstrap/Spinner'
import { Link } from 'react-router-dom'
import { useDashboard } from '../api/hooks'
import type { DashboardGroup } from '../api/types'
import StatTile from '../components/StatTile'
import StatusDot from '../components/StatusDot'
import { absoluteTime, relativeTime } from '../lib/time'

function GroupCard({ group, windowDays }: { group: DashboardGroup; windowDays: number }) {
  const finished = group.jobs.succeeded + group.jobs.failed
  const allOnline = group.deviceCount > 0 && group.onlineCount === group.deviceCount
  return (
    <Card as={Link} to={`/groups/${group.id}`} className="h-100 text-decoration-none text-body">
      <Card.Body>
        <div className="d-flex justify-content-between align-items-start gap-2">
          <Card.Title className="h6 mb-1">{group.name}</Card.Title>
          {group.deviceCount === 0 ? (
            <Badge bg="secondary">Empty</Badge>
          ) : (
            <Badge bg={allOnline ? 'success' : group.onlineCount === 0 ? 'danger' : 'warning'}
                   text={allOnline || group.onlineCount === 0 ? undefined : 'dark'}>
              {group.onlineCount}/{group.deviceCount} online
            </Badge>
          )}
        </div>
        {group.description && <div className="text-secondary small mb-2">{group.description}</div>}
        <div className="small mb-2">
          <StatusDot variant="success" label="succeeded" count={group.jobs.succeeded} />
          <StatusDot variant="danger" label="failed" count={group.jobs.failed} />
          {group.jobs.active > 0 && <StatusDot variant="primary" label="running" count={group.jobs.active} />}
          {finished === 0 && group.jobs.active === 0 && (
            <span className="text-secondary">No jobs in the last {windowDays} days</span>
          )}
        </div>
        <div className="text-secondary small">
          <span title={absoluteTime(group.lastContactAt)}>Last contact: {relativeTime(group.lastContactAt)}</span>
          <span className="mx-1">·</span>
          <span title={absoluteTime(group.lastJobAt)}>Last execution: {relativeTime(group.lastJobAt)}</span>
        </div>
      </Card.Body>
    </Card>
  )
}

export default function Dashboard() {
  const { data, isLoading, isError } = useDashboard()

  if (isLoading) return <Spinner role="status" aria-label="Loading dashboard" />
  if (isError || !data)
    return <Alert variant="danger">The dashboard could not be loaded. Retrying automatically…</Alert>

  const { devices, jobs, repos, groups, windowDays } = data
  const finished = jobs.succeeded + jobs.failed
  const successRate = finished > 0 ? Math.round((jobs.succeeded / finished) * 100) : null

  return (
    <>
      <h1 className="h3 mb-3">Dashboard</h1>

      {devices.total === 0 && (
        <Alert variant="secondary">
          Welcome to Svenager! No devices are enrolled yet — start on the{' '}
          <Link to="/enrollment">Enrollment</Link> page.
        </Alert>
      )}

      <Row xs={2} lg={4} className="g-3 mb-4">
        <Col>
          <StatTile
            label="Devices online"
            value={`${devices.online} / ${devices.total}`}
            detail={
              devices.offline > 0 ? (
                <StatusDot variant="danger" label="offline" count={devices.offline} />
              ) : devices.total > 0 ? (
                <span className="text-secondary">All devices are online</span>
              ) : undefined
            }
          />
        </Col>
        <Col>
          <StatTile
            label={`Job success (${windowDays} days)`}
            value={successRate != null ? `${successRate}%` : '—'}
            detail={
              finished > 0 ? (
                <>
                  <StatusDot variant="success" label="succeeded" count={jobs.succeeded} />
                  <StatusDot variant="danger" label="failed" count={jobs.failed} />
                </>
              ) : (
                <span className="text-secondary">No finished jobs yet</span>
              )
            }
          />
        </Col>
        <Col>
          <StatTile
            label="Jobs in progress"
            value={String(jobs.active)}
            detail={
              jobs.active > 0 ? (
                <Link to="/jobs">View jobs</Link>
              ) : (
                <span className="text-secondary">Queue is empty</span>
              )
            }
          />
        </Col>
        <Col>
          <StatTile
            label="Ansible sources"
            value={String(repos.total)}
            detail={
              repos.errors > 0 ? (
                <Link to="/sources" className="text-danger">
                  {repos.errors} with sync errors
                </Link>
              ) : repos.neverSynced > 0 ? (
                <Link to="/sources">{repos.neverSynced} never synced</Link>
              ) : repos.total > 0 ? (
                <span className="text-secondary">All synced</span>
              ) : (
                <Link to="/sources">Add a repository</Link>
              )
            }
          />
        </Col>
      </Row>

      {devices.ungrouped > 0 && (
        <Alert variant="warning">
          {devices.ungrouped} {devices.ungrouped === 1 ? 'device is' : 'devices are'} not in any group and will not
          receive configuration. Assign {devices.ungrouped === 1 ? 'it' : 'them'} on the{' '}
          <Link to="/devices">Devices</Link> page.
        </Alert>
      )}

      <h2 className="h5 mb-3">Groups</h2>
      {groups.length === 0 ? (
        <Alert variant="secondary">
          No groups yet — create one on the <Link to="/groups">Groups</Link> page to organize your devices.
        </Alert>
      ) : (
        <Row xs={1} md={2} xl={3} className="g-3">
          {groups.map((group) => (
            <Col key={group.id}>
              <GroupCard group={group} windowDays={windowDays} />
            </Col>
          ))}
        </Row>
      )}
    </>
  )
}
