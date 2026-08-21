import { useEffect, useRef, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import Spinner from 'react-bootstrap/Spinner'
import RFB from '@novnc/novnc'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  useCloseRemoteSession,
  useDevice,
  useDeviceRemoteSessions,
  useOpenRemoteSession,
  useRemoteSession,
} from '../api/hooks'

function remainingSeconds(expiresAt: string | null): number | null {
  if (!expiresAt) return null
  return Math.max(0, Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000))
}

function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

/**
 * Live VNC view of one device: opens a remote session, waits for the agent to
 * dial its reverse tunnel (delivered at the next check-in) and then attaches
 * noVNC to the server-side broker. Sessions are time-limited and audited.
 */
export default function RemoteView() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { data: device } = useDevice(id ?? null)
  const opened = useOpenRemoteSession(id ?? null)
  const closeSession = useCloseRemoteSession()
  const sessionId = opened.data?.sessionId ?? null
  const { data: session } = useRemoteSession(sessionId)

  const screenRef = useRef<HTMLDivElement>(null)
  const rfbRef = useRef<RFB | null>(null)
  const [connected, setConnected] = useState(false)
  const [viewOnly, setViewOnly] = useState(false)
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    if (!session || rfbRef.current || !screenRef.current) return
    if (session.status !== 'AGENT_CONNECTED') return
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const rfb = new RFB(screenRef.current, `${proto}://${window.location.host}${session.wsPath}`)
    rfb.scaleViewport = true
    rfb.focusOnClick = true
    rfb.background = 'transparent'
    rfb.addEventListener('connect', () => {
      setConnected(true)
      rfb.focus({ preventScroll: true })
    })
    rfb.addEventListener('disconnect', () => setConnected(false))
    rfbRef.current = rfb
  }, [session])

  // Countdown tick + teardown on unmount (the broker closes the audited
  // session as soon as the viewer socket goes away).
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => {
      clearInterval(timer)
      rfbRef.current?.disconnect()
      rfbRef.current = null
    }
  }, [])

  useEffect(() => {
    if (rfbRef.current) rfbRef.current.viewOnly = viewOnly
  }, [viewOnly])

  // Every viewer has their own session/tunnel — list who else is live.
  const { data: deviceSessions } = useDeviceRemoteSessions(id ?? null)
  const otherViewers = (deviceSessions ?? [])
    .filter((s) => s.status === 'ACTIVE' && s.sessionId !== sessionId)
    .map((s) => s.requestedBy ?? 'unknown')

  const hostname = device?.hostname ?? session?.hostname ?? '…'
  const closed = session?.status === 'CLOSED'
  const waiting = !closed && !connected && !opened.isError
  void now // re-render driver for the countdown
  const remaining = remainingSeconds(session?.expiresAt ?? null)

  const endSession = () => {
    if (sessionId) closeSession.mutate(sessionId)
  }

  // The open-session POST is get-or-create: refetching it after a close
  // creates a fresh session — no full page reload needed.
  const startNewSession = () => {
    rfbRef.current = null
    setConnected(false)
    opened.refetch()
  }

  return (
    <>
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <Link to="/devices">Devices</Link>
          </li>
          <li className="breadcrumb-item">
            <Link to={`/devices/${id}`}>{hostname}</Link>
          </li>
          <li className="breadcrumb-item active" aria-current="page">
            Remote view
          </li>
        </ol>
      </nav>

      <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
        <h1 className="h3 mb-0">Remote view: {hostname}</h1>
        {connected && <Badge bg="success">Live</Badge>}
        {connected && remaining != null && (
          <Badge bg={remaining < 60 ? 'danger' : 'secondary'} title="Sessions are time-limited">
            ends in {formatCountdown(remaining)}
          </Badge>
        )}
        {connected && (
          <Form.Check
            type="switch"
            id="view-only"
            label="View only"
            checked={viewOnly}
            onChange={(e) => setViewOnly(e.target.checked)}
            className="ms-2"
          />
        )}
        {connected && !viewOnly && (
          <Button size="sm" variant="outline-secondary" onClick={() => rfbRef.current?.sendCtrlAltDel()}>
            Ctrl+Alt+Del
          </Button>
        )}
        {otherViewers.length > 0 && (
          <Badge bg="info" title="Each viewer has their own audited session">
            also viewing: {otherViewers.join(', ')}
          </Badge>
        )}
        <Button size="sm" variant="outline-danger" className="ms-auto" onClick={endSession} disabled={!sessionId || closed}>
          End session
        </Button>
      </div>

      {opened.isError && (
        <Alert variant="danger">
          The remote session could not be opened. <Button size="sm" variant="outline-danger" onClick={() => opened.refetch()}>Try again</Button>
        </Alert>
      )}

      {closed && (
        <Alert variant="secondary">
          <Alert.Heading className="h6">Session ended</Alert.Heading>
          <p className="mb-2">{session?.closeReason ?? 'The remote session is over.'}</p>
          <div className="d-flex gap-2">
            <Button size="sm" onClick={startNewSession}>
              Start a new session
            </Button>
            <Button size="sm" variant="outline-secondary" onClick={() => navigate(`/devices/${id}`)}>
              Back to device
            </Button>
          </div>
        </Alert>
      )}

      {waiting && (
        <Alert variant="info" className="d-flex align-items-center gap-3">
          <Spinner size="sm" role="status" aria-label="Connecting" />
          <div>
            {session?.status === 'AGENT_CONNECTED'
              ? 'Device connected — attaching the viewer…'
              : 'Waiting for the device to pick up the tunnel. It fetches work at its next check-in (about once a minute).'}
            {device && !device.online && (
              <div className="small text-danger mt-1">
                This device looks offline — the session will time out if it does not check in.
              </div>
            )}
          </div>
        </Alert>
      )}

      <div
        ref={screenRef}
        className="border rounded bg-dark"
        style={{ height: '75vh', overflow: 'hidden', display: closed ? 'none' : undefined }}
        aria-label="Remote screen"
      />
    </>
  )
}
